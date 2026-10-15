package edu.demo.campusactivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Service
class PersonalParticipationPdfService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.of("Asia/Shanghai"));
    private static final int MAX_RECORDS = 1000;
    private static final int ROWS_PER_PAGE = 9;
    private final CurrentUser current;
    private final RegistrationService registrations;
    private final ActivityRepository activities;
    private final ParticipationExportAuditRepository audits;

    PersonalParticipationPdfService(CurrentUser current, RegistrationService registrations,
                                    ActivityRepository activities, ParticipationExportAuditRepository audits) {
        this.current = current;
        this.registrations = registrations;
        this.activities = activities;
        this.audits = audits;
    }

    @Transactional
    byte[] export(String query) {
        UserAccount student = current.require(UserAccount.Role.STUDENT);
        if (current.miniappAttribution() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "请通过已绑定微信的小程序导出本人记录");
        }
        String keyword = query == null ? "" : query.trim();
        if (keyword.length() > 80) throw new ApiException(HttpStatus.BAD_REQUEST, "搜索词不能超过80字");
        List<RegistrationView> checked = registrations.mine().stream()
            .filter(row -> row.checkedAt() != null)
            .filter(row -> keyword.isEmpty() || row.activityTitle().toLowerCase(Locale.ROOT)
                .contains(keyword.toLowerCase(Locale.ROOT)))
            .toList();
        if (checked.size() > MAX_RECORDS) {
            throw new ApiException(HttpStatus.CONFLICT, "记录过多，请先按活动名称筛选后导出");
        }
        Map<Long, Activity> byId = new HashMap<>();
        activities.findAllById(checked.stream().map(RegistrationView::activityId).toList())
            .forEach(activity -> {
                Activity loaded = org.hibernate.Hibernate.unproxy(activity, Activity.class);
                byId.put(loaded.getId(), loaded);
            });
        byte[] result = render(student, checked, byId);
        audits.saveAndFlush(new ParticipationExportAudit(student, null, "STUDENT",
            "PERSONAL_CHECKINS", "PDF", checked.size()));
        return result;
    }

    private byte[] render(UserAccount student, List<RegistrationView> checked,
                          Map<Long, Activity> byId) {
        try (PDDocument document = new PDDocument();
             InputStream fontFile = Objects.requireNonNull(getClass().getResourceAsStream(
                 "/fonts/NotoSansSC.ttf"), "PDF font missing");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType0Font font = PDType0Font.load(document, fontFile, true);
            int pages = Math.max(1, (checked.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
            for (int pageIndex = 0; pageIndex < pages; pageIndex++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream canvas = new PDPageContentStream(document, page)) {
                    write(canvas, font, 19, 48, 786, "个人活动参与记录（非官方）", 490);
                    write(canvas, font, 10, 48, 760, "仅供本人查阅，不作为学校或活动组织方出具的证明", 490);
                    write(canvas, font, 11, 48, 724, "姓名：" + student.name + "    学号："
                        + Objects.toString(student.studentNumber, "未设置"), 490);
                    write(canvas, font, 9, 48, 704, "生成时间：" + TIME.format(Instant.now())
                        + "    已签到活动：" + checked.size() + " 场", 490);
                    int from = pageIndex * ROWS_PER_PAGE;
                    int to = Math.min(checked.size(), from + ROWS_PER_PAGE);
                    if (checked.isEmpty()) write(canvas, font, 11, 48, 656, "暂无符合条件的已签到活动", 490);
                    for (int index = from; index < to; index++) {
                        RegistrationView row = checked.get(index);
                        Activity activity = byId.get(row.activityId());
                        float y = 654 - (index - from) * 66;
                        write(canvas, font, 11, 48, y, (index + 1) + ". " + row.activityTitle(), 490);
                        write(canvas, font, 9, 63, y - 19, "活动时间："
                            + (activity == null ? "记录缺失" : TIME.format(activity.startsAt) + " 至 "
                                + TIME.format(activity.endsAt)), 475);
                        write(canvas, font, 9, 63, y - 37, "地点："
                            + (activity == null ? "记录缺失" : activity.location)
                            + "    签到：" + TIME.format(row.checkedAt()), 475);
                    }
                    write(canvas, font, 9, 48, 38, "本文件为平台个人记录，非官方证明。  "
                        + (pageIndex + 1) + " / " + pages, 490);
                }
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException | IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "个人记录生成失败，请稍后重试");
        }
    }

    private void write(PDPageContentStream canvas, PDType0Font font, int size, float x, float y,
                       String value, float maxWidth) throws IOException {
        String text = fit(font, size, value, maxWidth);
        canvas.beginText();
        canvas.setFont(font, size);
        canvas.newLineAtOffset(x, y);
        canvas.showText(text);
        canvas.endText();
    }

    private String fit(PDType0Font font, int size, String value, float maxWidth) throws IOException {
        StringBuilder result = new StringBuilder();
        for (int point : Objects.toString(value, "").codePoints().toArray()) {
            if (Character.isISOControl(point)) point = ' ';
            String next = new String(Character.toChars(point));
            try { font.getStringWidth(next); }
            catch (IllegalArgumentException exception) { next = "?"; }
            if (font.getStringWidth(result + next) * size / 1000 > maxWidth) {
                result.append("...");
                break;
            }
            result.append(next);
        }
        return result.toString();
    }
}

@RestController
@RequestMapping("/api/miniapp/my")
class PersonalParticipationPdfController {
    private final PersonalParticipationPdfService pdf;

    PersonalParticipationPdfController(PersonalParticipationPdfService pdf) { this.pdf = pdf; }

    @GetMapping("/participation.pdf")
    ResponseEntity<byte[]> export(@RequestParam(required = false) String query) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=personal-participation.pdf")
            .body(pdf.export(query));
    }
}
