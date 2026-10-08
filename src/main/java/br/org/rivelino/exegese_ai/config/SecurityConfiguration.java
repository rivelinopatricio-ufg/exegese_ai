/*******************************************************************************
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software 
 * and associated documentation files (the "Software"), to deal in the Software without 
 * restriction, including without limitation the rights to use, copy, modify, merge, publish, 
 * distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the 
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or 
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR 
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS 
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR 
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN 
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION 
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * This software uses third-party components, distributed backward accordingly to their own licenses.
 *******************************************************************************/
package br.org.rivelino.exegese_ai.config;

import br.org.rivelino.exegese_ai.security.AccountStatusFilter;
import br.org.rivelino.exegese_ai.security.CustomOidcUserService;
import br.org.rivelino.exegese_ai.security.GoogleOAuth2SuccessHandler;
import br.org.rivelino.exegese_ai.security.InputSanitizationFilter;
import br.org.rivelino.exegese_ai.security.OAuth2LoginFailureHandler;
import br.org.rivelino.exegese_ai.security.RateLimitFilter;
import br.org.rivelino.exegese_ai.security.UserAccountStatusCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Spring Security configuration enforcing Google OAuth2 login and role-based access control.
 *
 * @author Rivelino Patrício
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfiguration {

    /**
     * Strict Content-Security-Policy: scripts and styles only from this origin (no 'unsafe-inline', no CDN).
     * Templates load the self-hosted Tailwind build (/css/tailwind.css) and external scripts from /js/,
     * and wire events with addEventListener instead of inline handlers. img-src also admits Google
     * profile pictures (*.googleusercontent.com); fetch() and EventSource stay same-origin.
     */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; "
            + "script-src 'self'; "
            + "style-src 'self'; "
            + "img-src 'self' data: https://*.googleusercontent.com; "
            + "connect-src 'self'; "
            + "font-src 'self'; "
            + "object-src 'none'; "
            + "base-uri 'self'; "
            + "form-action 'self'; "
            + "frame-ancestors 'none'";

    /** Browser features the application never uses are disabled for every page. */
    static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=()";

    private final CustomOidcUserService customOidcUserService;
    private final GoogleOAuth2SuccessHandler successHandler;
    private final OAuth2LoginFailureHandler failureHandler;
    private final RateLimitFilter rateLimitFilter;
    private final InputSanitizationFilter inputSanitizationFilter;
    private final UserAccountStatusCache accountStatusCache;

    public SecurityConfiguration(CustomOidcUserService customOidcUserService,
                                 GoogleOAuth2SuccessHandler successHandler,
                                 OAuth2LoginFailureHandler failureHandler,
                                 RateLimitFilter rateLimitFilter,
                                 InputSanitizationFilter inputSanitizationFilter,
                                 UserAccountStatusCache accountStatusCache) {
        this.customOidcUserService = customOidcUserService;
        this.successHandler = successHandler;
        this.failureHandler = failureHandler;
        this.rateLimitFilter = rateLimitFilter;
        this.inputSanitizationFilter = inputSanitizationFilter;
        this.accountStatusCache = accountStatusCache;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .headers(headers -> headers
                .contentTypeOptions(org.springframework.security.config.Customizer.withDefaults())
                .frameOptions(frame -> frame.deny())
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY))
                .crossOriginOpenerPolicy(coop -> coop.policy(CrossOriginOpenerPolicy.SAME_ORIGIN))
            )
            .addFilterBefore(inputSanitizationFilter, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(rateLimitFilter, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            // Re-validates active flag and role of the authenticated account before authorization decisions
            .addFilterBefore(new AccountStatusFilter(accountStatusCache), AuthorizationFilter.class)
            // API calls get HTTP 401 instead of a redirect to the HTML login page
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(DelegatingAuthenticationEntryPoint.builder()
                    .addEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                            PathPatternRequestMatcher.pathPattern("/api/**"))
                    .defaultEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"))
                    .build())
            )
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/login", "/error", "/css/**", "/js/**", "/images/**", "/actuator/health", "/favicon.ico").permitAll()
                .requestMatchers("/admin/users/**", "/admin/models/**").hasRole("ADMIN")
                .requestMatchers("/admin/subjects/**", "/admin/documents/**").hasAnyRole("ADMIN", "OPERATOR")
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(customOidcUserService))
                .successHandler(successHandler)
                .failureHandler(failureHandler)
            )
            .logout(logout -> logout
                .logoutRequestMatcher(PathPatternRequestMatcher.pathPattern("/logout"))
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            );

        return http.build();
    }
}
