package ro.kf.sdk.spring;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Put it on a {@code @Configuration} / {@code @SpringBootApplication} class that declares a {@code KfModule} bean:
 *
 * <pre>{@code
 * @SpringBootApplication
 * @EnableKfModule
 * class App {
 *     @Bean KfModule kfModule() {
 *         KfModule kf = KfModule.fromEnv();
 *         kf.models().beforeCreate(ctx -> ctx.allow());
 *         return kf;
 *     }
 * }
 * }</pre>
 *
 * The SDK then serves {@code POST /_kf/hooks} and registers the module once the context has started.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import(KfSpringConfiguration.class)
public @interface EnableKfModule {
}
