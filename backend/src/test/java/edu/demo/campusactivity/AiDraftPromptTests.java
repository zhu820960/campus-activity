package edu.demo.campusactivity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AiDraftPromptTests {
    @Test
    void sparseActivityOmitsMissingFieldsAndUsesLeadGuidance() {
        var request = new AiDraftController.DraftRequest(null, AiDraftController.Kind.LEAD,
            "关注大学生心理健康", "心理辅导", "西安欧亚学院", null, null, null,
            null, null, AiDraftController.Style.WARM);
        String prompt = AiDraftController.userPrompt(request, null);
        assertTrue(prompt.contains("不以活动名称开头"));
        assertTrue(prompt.contains("温暖、亲切"));
        assertTrue(prompt.contains("活动名称：心理辅导"));
        assertFalse(prompt.contains("未填写"));
        assertFalse(prompt.contains("开始时间："));
        assertFalse(prompt.contains("活动说明："));
    }

    @Test
    void revisionKeepsFactsButRequestsNewExpression() {
        var request = new AiDraftController.DraftRequest(null, AiDraftController.Kind.LEAD,
            null, "心理辅导", null, null, null, null,
            "心理辅导活动即将开展。", "更简短", AiDraftController.Style.NATURAL);
        String prompt = AiDraftController.userPrompt(request, null);
        assertTrue(prompt.contains("现有草稿"));
        assertTrue(prompt.contains("修改方向：更简短"));
        assertTrue(prompt.contains("保留事实"));
    }
}
