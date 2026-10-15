package edu.demo.campusactivity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:context_load;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.jpa.hibernate.ddl-auto=create-drop"
})
class CampusActivityApplicationTests {

	@Test
	void contextLoads() {
	}

}
