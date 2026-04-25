package am.ik.blog;

import am.ik.blog.counter.CounterApiProps;
import am.ik.blog.entry.EntryApiProps;
import am.ik.blog.entry.GiscusProps;
import am.ik.blog.site.AboutProps;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ BlogProps.class, AboutProps.class, GiscusProps.class, EntryApiProps.class,
		CounterApiProps.class })
public class BlogFrontendHtmxApplication {

	public static void main(String[] args) {
		SpringApplication.run(BlogFrontendHtmxApplication.class, args);
	}

}
