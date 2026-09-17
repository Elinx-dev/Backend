package in.gov.slate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SlateApplication {
    public static void main(String[] args) {
        SpringApplication.run(SlateApplication.class, args);
    }
}
