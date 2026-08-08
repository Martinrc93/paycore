package dev.martin.paycore;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;

@SpringBootApplication(exclude = OAuth2ClientAutoConfiguration.class)
public class PaycoreApplication {

	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(PaycoreApplication.class, args);
	}

}
