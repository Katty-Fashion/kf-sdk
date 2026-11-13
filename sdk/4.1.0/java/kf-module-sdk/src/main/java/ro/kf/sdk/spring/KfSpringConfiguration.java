package ro.kf.sdk.spring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import ro.kf.sdk.HookResult;
import ro.kf.sdk.KfModule;
import ro.kf.sdk.RequestContext;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Spring MVC adapter (imported by {@link EnableKfModule}): mounts {@code POST /_kf/hooks} for the application's
 * {@link KfModule} bean, serves the module's UI bundles ({@code GET /ui/**} from {@code classpath:static/ui/}), verifies
 * the context on the module's own routes and calls {@link KfModule#start()} when the context starts.
 *
 * <p><strong>The module's own routes.</strong> Every request outside {@link KfModule#isPublicPath} (the
 * hook path, {@code /ui/**}, {@code /health} and the prefixes added with {@link KfModule#publicPath}) must carry a
 * context signed by the platform for its method and path; otherwise it is answered {@code 401} with an RFC 9457 body
 * ({@code class: permanent}) and never reaches a controller. A controller reads the verified context as a
 * {@link RequestContext} parameter (or the request attribute {@link RequestContext#ATTRIBUTE}).
 */
@Configuration(proxyBeanMethods = false)
public class KfSpringConfiguration {

    /** Where UI bundles live in the jar, and the URL prefix they are served under (after Traefik strips /m/{slug}). */
    public static final String UI_LOCATION = "static/ui/";
    public static final String UI_PATH = "/ui/";

    /**
     * Explicit MIME types: {@code .mjs} MUST be {@code text/javascript}, browsers refuse module scripts served as
     * application/octet-stream (which is what Spring's static resource handling may pick for .mjs).
     */
    private static final Map<String, MediaType> UI_TYPES = Map.of(
            "mjs", MediaType.parseMediaType("text/javascript;charset=UTF-8"),
            "js", MediaType.parseMediaType("text/javascript;charset=UTF-8"),
            "css", MediaType.parseMediaType("text/css;charset=UTF-8"),
            "map", MediaType.APPLICATION_JSON,
            "json", MediaType.APPLICATION_JSON,
            "svg", MediaType.parseMediaType("image/svg+xml"),
            "png", MediaType.IMAGE_PNG,
            "woff2", MediaType.parseMediaType("font/woff2"));

    /** {@code GET|HEAD /ui/**}: the module's UI contributions (ES modules + CSS), no-cache so new versions show up at once. */
    @Bean
    RouterFunction<ServerResponse> kfModuleUiRoute() {
        return RouterFunctions.route()
                .GET(UI_PATH + "**", request -> serveUi(request.path()))
                .HEAD(UI_PATH + "**", request -> serveUi(request.path()))
                .build();
    }

    private static ServerResponse serveUi(String path) {
        String rel = path.substring(UI_PATH.length());
        int dot = rel.lastIndexOf('.');
        MediaType type = dot < 0 ? null : UI_TYPES.get(rel.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (rel.isEmpty() || type == null || rel.contains("..") || rel.contains("\\") || rel.startsWith("/")) {
            return ServerResponse.notFound().build();
        }
        ClassPathResource resource = new ClassPathResource(UI_LOCATION + rel);
        if (!resource.exists() || !resource.isReadable()) return ServerResponse.notFound().build();
        return ServerResponse.ok()
                .contentType(type)
                .cacheControl(CacheControl.noCache())
                .header("X-Content-Type-Options", "nosniff")
                .body(resource);
    }

    @Bean
    RouterFunction<ServerResponse> kfModuleHookRoute(KfModule kf) {
        return RouterFunctions.route()
                .POST(kf.hookPath(), request -> {
                    byte[] body = request.body(byte[].class);
                    HookResult result = kf.handle(body == null ? new byte[0] : body,
                            request.headers().firstHeader("X-KF-Timestamp"),
                            request.headers().firstHeader("X-KF-Signature"),
                            request.headers().firstHeader(RequestContext.CONTEXT_HEADER),
                            request.headers().firstHeader(RequestContext.PROOF_HEADER));
                    return ServerResponse.status(result.status()).contentType(MediaType.parseMediaType(result.contentType()))
                            .body(result.body());
                })
                .build();
    }

    /** The context on the module's own routes (see the class javadoc). */
    @Bean
    OncePerRequestFilter kfModuleContextFilter(KfModule kf) {
        return new ContextFilter(kf);
    }

    /** {@code RequestContext} as a controller parameter: the verified context of the request (null on a public path). */
    @Bean
    WebMvcConfigurer kfModuleRequestContextResolver() {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return RequestContext.class.equals(parameter.getParameterType());
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                        return webRequest.getAttribute(RequestContext.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
                    }
                });
            }
        };
    }

    @Bean
    SmartLifecycle kfModuleLifecycle(KfModule kf) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                kf.start();
                running = true;
            }

            @Override
            public void stop() {
                kf.stop();
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            /** After the web server started, so callbacks can be served when the platform starts calling. */
            @Override
            public int getPhase() {
                return Integer.MAX_VALUE - 100;
            }
        };
    }

    /** Verifies the context before any controller; the body is read once and replayed to the controller. */
    static final class ContextFilter extends OncePerRequestFilter {

        private static final System.Logger LOG = System.getLogger("ro.kf.sdk");
        private final KfModule kf;

        ContextFilter(KfModule kf) {
            this.kf = kf;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return kf.isPublicPath(path(request));
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            byte[] body = request.getInputStream().readAllBytes();
            RequestContext ctx;
            try {
                ctx = kf.verifyContext(request.getHeader(RequestContext.CONTEXT_HEADER),
                        request.getHeader(RequestContext.TIMESTAMP_HEADER), request.getHeader(RequestContext.SIGNATURE_HEADER),
                        request.getHeader(RequestContext.PROOF_HEADER), body);
                String path = path(request);
                if (!ctx.method().equals(request.getMethod()) || !ctx.path().equals(path)) {
                    throw new RequestContext.Rejected("the context was signed for " + ctx.method() + " " + ctx.path()
                            + ", not for " + request.getMethod() + " " + path);
                }
            } catch (RequestContext.Rejected rejected) {
                LOG.log(System.Logger.Level.WARNING, "KF SDK: {0} {1} refused: {2}", request.getMethod(),
                        request.getRequestURI(), rejected.problem().detail());
                response.setStatus(401);
                response.setContentType("application/problem+json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(rejected.problemJson());
                return;
            }
            request.setAttribute(RequestContext.ATTRIBUTE, ctx);
            chain.doFilter(new CachedBody(request, body), response);
        }

        /** The decoded path the module received, without the servlet context path. */
        static String path(HttpServletRequest request) {
            String raw = request.getRequestURI().substring(request.getContextPath().length());
            try {
                return URI.create(raw).getPath();
            } catch (IllegalArgumentException e) {
                return raw;
            }
        }
    }

    /** A request whose body was already read by the filter. */
    static final class CachedBody extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("synchronous body only");
                }

                @Override
                public int read() {
                    return in.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
