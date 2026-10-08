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
 * This software uses third-party components, distributed accordingly to their own licenses.
 *******************************************************************************/
package br.org.rivelino.exegese_ai.security;

import br.org.rivelino.exegese_ai.domain.entity.ExegeseUser;
import br.org.rivelino.exegese_ai.service.GoogleIdentityMismatchException;
import br.org.rivelino.exegese_ai.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Custom OIDC User Service mapping Google user details and synchronizing local accounts.
 * This is the single place where a Google login is accepted or rejected and where the local account
 * is synchronized: the e-mail must be verified by Google, belong to an allowed domain (when
 * {@code exegese.security.allowed-email-domains} is set) and must not belong to a deactivated account.
 * With a domain allowlist the Google account must also be managed by that Google Workspace domain (the
 * {@code hd} claim), unless {@code exegese.security.require-hosted-domain} is false: a consumer Google account
 * may keep a verified address of a domain long after the mailbox was revoked. Local accounts are bound to the
 * Google subject ({@code sub}) on the first login, and a login asserting the e-mail of an account bound to
 * another subject is refused.
 *
 * @author Rivelino Patrício
 */
@Service
public class CustomOidcUserService extends OidcUserService {

    private static final Logger log = LoggerFactory.getLogger(CustomOidcUserService.class);

    public static final String ERROR_ACCOUNT_DISABLED = "account_disabled";
    public static final String ERROR_EMAIL_NOT_VERIFIED = "email_not_verified";
    public static final String ERROR_EMAIL_DOMAIN_NOT_ALLOWED = "email_domain_not_allowed";
    public static final String ERROR_ACCOUNT_IDENTITY_MISMATCH = "account_identity_mismatch";

    /** Consumer Google mail domains: their accounts never carry an {@code hd} (hosted domain) claim. */
    private static final Set<String> CONSUMER_GOOGLE_DOMAINS = Set.of("gmail.com", "googlemail.com");

    private final UserService userService;
    private final Set<String> allowedEmailDomains;
    private final boolean requireHostedDomain;

    public CustomOidcUserService(UserService userService,
                                 @Value("${exegese.security.allowed-email-domains:}") String allowedEmailDomains,
                                 @Value("${exegese.security.require-hosted-domain:true}") boolean requireHostedDomain) {
        this.userService = userService;
        this.allowedEmailDomains = parseDomains(allowedEmailDomains);
        this.requireHostedDomain = requireHostedDomain;
        if (this.allowedEmailDomains.isEmpty()) {
            log.info("Google login open to any verified Google account (exegese.security.allowed-email-domains is empty)");
        } else {
            log.info("Google login restricted to e-mail domains: {} (Google Workspace hosted domain required: {})",
                    this.allowedEmailDomains, requireHostedDomain);
            if (requireHostedDomain && this.allowedEmailDomains.stream().anyMatch(CONSUMER_GOOGLE_DOMAINS::contains)) {
                log.warn("exegese.security.allowed-email-domains lists a consumer Google domain, whose accounts have no "
                        + "hosted domain (hd) claim and are refused while exegese.security.require-hosted-domain "
                        + "(REQUIRE_HOSTED_DOMAIN) is true");
            }
        }
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);
        return authorizeAndSynchronize(oidcUser);
    }

    /**
     * Applies the login policy to an OIDC user returned by Google and synchronizes the local account.
     *
     * @param oidcUser OIDC user built from the Google ID token and user info
     * @return OIDC user carrying the local role as an authority
     * @throws OAuth2AuthenticationException with error code {@link #ERROR_EMAIL_NOT_VERIFIED},
     *         {@link #ERROR_EMAIL_DOMAIN_NOT_ALLOWED}, {@link #ERROR_ACCOUNT_IDENTITY_MISMATCH} or
     *         {@link #ERROR_ACCOUNT_DISABLED} when the login is refused
     */
    OidcUser authorizeAndSynchronize(OidcUser oidcUser) {
        String email = oidcUser.getEmail();
        if (email == null || email.isBlank() || !Boolean.TRUE.equals(oidcUser.getEmailVerified())) {
            throw reject(ERROR_EMAIL_NOT_VERIFIED, "Google account e-mail is missing or not verified");
        }
        if (!isAllowedDomain(email)) {
            throw reject(ERROR_EMAIL_DOMAIN_NOT_ALLOWED, "Google account e-mail domain is not allowed");
        }
        if (!isManagedByAllowedDomain(email, oidcUser.getClaimAsString("hd"))) {
            throw reject(ERROR_EMAIL_DOMAIN_NOT_ALLOWED, "Google account is not managed by an allowed Workspace domain");
        }
        String subject = oidcUser.getSubject();
        if (subject == null || subject.isBlank()) {
            throw reject(ERROR_ACCOUNT_IDENTITY_MISMATCH, "Google account subject is missing");
        }

        Optional<ExegeseUser> existing = userService.findByGoogleSub(subject).or(() -> userService.findByEmail(email));
        if (existing.isPresent()) {
            ExegeseUser account = existing.get();
            if (account.getGoogleSub() != null && !account.getGoogleSub().equals(subject)) {
                throw reject(ERROR_ACCOUNT_IDENTITY_MISMATCH, "Local account is bound to another Google subject");
            }
            if (!account.isActive()) {
                throw reject(ERROR_ACCOUNT_DISABLED, "Local account is deactivated");
            }
        }

        String name = oidcUser.getFullName() != null ? oidcUser.getFullName() : oidcUser.getGivenName();
        if (name == null || name.isBlank()) {
            name = email;
        }
        ExegeseUser appUser;
        try {
            appUser = userService.syncGoogleUser(email, name, oidcUser.getPicture(), subject);
        } catch (GoogleIdentityMismatchException e) {
            throw reject(ERROR_ACCOUNT_IDENTITY_MISMATCH, e.getMessage());
        }
        if (!appUser.isActive()) {
            throw reject(ERROR_ACCOUNT_DISABLED, "Local account is deactivated");
        }

        Set<GrantedAuthority> authorities = new HashSet<>(oidcUser.getAuthorities());
        authorities.add(new SimpleGrantedAuthority(appUser.getRole().name()));

        return new DefaultOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo());
    }

    private boolean isAllowedDomain(String email) {
        if (allowedEmailDomains.isEmpty()) {
            return true;
        }
        int at = email.lastIndexOf('@');
        return at >= 0 && allowedEmailDomains.contains(email.substring(at + 1).trim().toLowerCase(Locale.ROOT));
    }

    /**
     * With a domain allowlist (and {@code require-hosted-domain}), the {@code hd} claim must name the e-mail
     * domain itself, and that domain must be allowed: only then is the account managed by the domain owner.
     */
    private boolean isManagedByAllowedDomain(String email, String hostedDomain) {
        if (allowedEmailDomains.isEmpty() || !requireHostedDomain) {
            return true;
        }
        if (hostedDomain == null || hostedDomain.isBlank()) {
            return false;
        }
        String hd = hostedDomain.trim().toLowerCase(Locale.ROOT);
        String emailDomain = email.substring(email.lastIndexOf('@') + 1).trim().toLowerCase(Locale.ROOT);
        return allowedEmailDomains.contains(hd) && hd.equals(emailDomain);
    }

    private static Set<String> parseDomains(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(domain -> domain.startsWith("@") ? domain.substring(1) : domain)
                .map(domain -> domain.toLowerCase(Locale.ROOT))
                .filter(domain -> !domain.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static OAuth2AuthenticationException reject(String errorCode, String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(errorCode, description, null), description);
    }
}
