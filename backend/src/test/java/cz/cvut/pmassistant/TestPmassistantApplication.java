package cz.cvut.pmassistant;

import org.springframework.boot.SpringApplication;

public class TestPmassistantApplication {

	public static void main(String[] args) {
		SpringApplication.from(PmassistantApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
