package com.finsight.config;

import com.finsight.auth.security.JwtAuthenticationFilter;
import com.finsight.auth.security.RestAccessDeniedHandler;
import com.finsight.auth.security.RestAuthenticationEntryPoint;
import com.finsight.user.domain.Role;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The application's security policy, expressed as one {@link SecurityFilterChain}.
 *
 * <h2>Why CSRF is disabled</h2>
 * CSRF protection defends against the browser automatically attaching an ambient
 * credential (a cookie) to a cross-site request. This API has no ambient
 * credential: it is stateless and authenticates only from an
 * {@code Authorization} header that a cross-origin page cannot cause the browser
 * to send. Disabling CSRF here is correct; it would be a serious mistake in a
 * cookie-session application.
 *
 * <h2>Why sessions are STATELESS</h2>
 * No {@code JSESSIONID} is created and the {@code SecurityContext} is rebuilt from
 * the token on every request. That is what lets several instances serve the same
 * client without sticky sessions or a shared session store.
 *
 * <h2>Deny by default</h2>
 * The chain ends with {@code anyRequest().authenticated()}. A new controller is
 * therefore protected the moment it is written; opening it up requires an explicit
 * line here. The reverse default — allow unless listed — is how endpoints get
 * shipped unprotected.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // enables @PreAuthorize / @PostAuthorize where a rule is method-specific
public class SecurityConfig {

    /** Everything under this prefix is public. */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh"
    };

    private static final String[] DOCS_ENDPOINTS = {
            "/v3/api-docs", "/v3/api-docs/**",
            "/swagger-ui.html", "/swagger-ui/**"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable())   // logout is client-side token disposal; see docs/07

            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(authenticationEntryPoint)   // 401
                    .accessDeniedHandler(accessDeniedHandler))            // 403

            .headers(headers -> headers
                    .frameOptions(frame -> frame.deny())
                    .contentTypeOptions(opts -> {})                       // X-Content-Type-Options: nosniff
                    .referrerPolicy(ref -> ref.policy(
                            org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                    .ReferrerPolicy.SAME_ORIGIN))
                    .httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .maxAgeInSeconds(31_536_000)))

            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                    .requestMatchers(DOCS_ENDPOINTS).permitAll()

                    // Liveness/readiness must answer an unauthenticated orchestrator.
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                    // Everything else under /actuator exposes internals.
                    .requestMatchers("/actuator/**").hasRole(Role.ROLE_ADMIN.shortName())

                    // CORS pre-flight carries no credentials and must not 401.
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                    .anyRequest().authenticated())

            // Runs before the username/password filter so a valid bearer token
            // populates the context before any other authentication is attempted.
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * BCrypt at strength 10 (~100 ms per hash on typical hardware).
     *
     * <p>Deliberately slow and salted per-password, which is what makes a stolen
     * {@code users} table expensive to crack. A general-purpose digest such as
     * SHA-256 — fast by design — would be the wrong tool here even with a salt.
     *
     * <p>Returning the {@link PasswordEncoder} interface rather than the concrete
     * type means a future move to Argon2 or a {@code DelegatingPasswordEncoder}
     * touches only this method.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * Wires the login path: look the user up, then compare the submitted password
     * against the stored BCrypt hash.
     */
    @Bean
    public DaoAuthenticationProvider authenticationProvider(UserDetailsService userDetailsService,
                                                            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // Keep "unknown email" indistinguishable from "wrong password" (default true,
        // set explicitly because it is a security property, not a style choice).
        provider.setHideUserNotFoundExceptions(true);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * Permissive CORS for local development only.
     *
     * <p>{@code allowedOriginPatterns} is restricted to localhost and
     * {@code allowCredentials} is false, because this API authenticates from a
     * header rather than a cookie. A real deployment must replace the origin list
     * with the actual front-end origins — flagged in docs/13 as deployment-specific
     * hardening.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With"));
        config.setExposedHeaders(List.of("Location"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
