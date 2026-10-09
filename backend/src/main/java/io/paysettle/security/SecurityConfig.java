package io.paysettle.security;

import io.paysettle.config.PaySettleProperties;
import io.paysettle.config.PaySettleProperties.SecurityMode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stateless API security with two roles and separation of duties:
 * <ul>
 *   <li>OPERATOR: submits ACH files and wires, runs settlement</li>
 *   <li>ANALYST: works fraud alerts (clearing an alert is what releases a held payment)</li>
 * </ul>
 * Modes: {@code api-key} (X-API-Key header; local and service-to-service) or {@code jwt} (OAuth2 bearer tokens from
 * the bank's identity provider, roles in the "roles" claim).
 */
@Configuration
public class SecurityConfig {

    public static final String OPERATOR = "OPERATOR";
    public static final String ANALYST = "ANALYST";

    @Bean
    SecurityFilterChain security(HttpSecurity http, PaySettleProperties props) throws Exception {
        http.csrf(c -> c.disable())   // no cookies: every request carries an API key or bearer token
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/actuator/**").hasAnyRole(OPERATOR, ANALYST)
                .requestMatchers(HttpMethod.POST, "/api/v1/fraud-alerts/**").hasRole(ANALYST)
                .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAnyRole(OPERATOR, ANALYST)
                .requestMatchers("/api/v1/**").hasRole(OPERATOR)
                .anyRequest().permitAll());   // the React console's static files
        if (props.security().securityMode() == SecurityMode.JWT) {
            http.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new RolesClaimConverter())));
        } else {
            http.addFilterBefore(new ApiKeyFilter(props.security()), UsernamePasswordAuthenticationFilter.class);
        }
        return http.build();
    }

    static final class ApiKeyFilter extends OncePerRequestFilter {
        private final PaySettleProperties.Security cfg;

        ApiKeyFilter(PaySettleProperties.Security cfg) {
            this.cfg = cfg;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String key = request.getHeader("X-API-Key");
            if (key != null) {
                String role = matches(key, cfg.operatorApiKey()) ? OPERATOR : matches(key, cfg.analystApiKey()) ? ANALYST : null;
                if (role != null) {
                    var auth = new UsernamePasswordAuthenticationToken(role.toLowerCase() + "-api-key", null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
            chain.doFilter(request, response);
        }

        private static boolean matches(String provided, String expected) {
            return expected != null && !expected.isBlank() && MessageDigest.isEqual(
                    provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
        }
    }

    static final class RolesClaimConverter implements Converter<Jwt, AbstractAuthenticationToken> {
        @Override
        public AbstractAuthenticationToken convert(Jwt jwt) {
            List<String> roles = jwt.getClaimAsStringList("roles");
            Collection<GrantedAuthority> authorities = roles == null ? List.of()
                    : roles.stream().map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r.toUpperCase())).toList();
            return new JwtAuthenticationToken(jwt, authorities, jwt.getClaimAsString("preferred_username") != null
                    ? jwt.getClaimAsString("preferred_username") : jwt.getSubject());
        }
    }
}
