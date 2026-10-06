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

import br.org.rivelino.exegese_ai.security.CustomOidcUserService;
import br.org.rivelino.exegese_ai.security.GoogleOAuth2SuccessHandler;
import br.org.rivelino.exegese_ai.security.InputSanitizationFilter;
import br.org.rivelino.exegese_ai.security.RateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration enforcing Google OAuth2 login and role-based access control.
 *
 * @author Rivelino Patrício
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfiguration {

    private final CustomOidcUserService customOidcUserService;
    private final GoogleOAuth2SuccessHandler successHandler;
    private final RateLimitFilter rateLimitFilter;
    private final InputSanitizationFilter inputSanitizationFilter;

    public SecurityConfiguration(CustomOidcUserService customOidcUserService,
                                 GoogleOAuth2SuccessHandler successHandler,
                                 RateLimitFilter rateLimitFilter,
                                 InputSanitizationFilter inputSanitizationFilter) {
        this.customOidcUserService = customOidcUserService;
        this.successHandler = successHandler;
        this.rateLimitFilter = rateLimitFilter;
        this.inputSanitizationFilter = inputSanitizationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .headers(headers -> headers
                .contentTypeOptions(org.springframework.security.config.Customizer.withDefaults())
                .frameOptions(frame -> frame.deny())
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self' 'unsafe-inline' https://cdn.tailwindcss.com; style-src 'self' 'unsafe-inline' https://cdn.tailwindcss.com; img-src 'self' data:; connect-src 'self';"))
            )
            .addFilterBefore(inputSanitizationFilter, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(rateLimitFilter, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
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
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            );

        return http.build();
    }
}
