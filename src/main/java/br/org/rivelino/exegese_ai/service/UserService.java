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
package br.org.rivelino.exegese_ai.service;

import br.org.rivelino.exegese_ai.domain.dto.SubjectPermissionDTO;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseSubject;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseUser;
import br.org.rivelino.exegese_ai.domain.entity.UserSubjectPermission;
import br.org.rivelino.exegese_ai.domain.entity.UserSubjectPermissionId;
import br.org.rivelino.exegese_ai.domain.enums.SubjectPermissionLevel;
import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseUserRepository;
import br.org.rivelino.exegese_ai.repository.UserSubjectPermissionRepository;
import br.org.rivelino.exegese_ai.security.UserAccountStatusCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing user accounts, Google OAuth2 provisioning and RBAC roles.
 * Administrative changes are guarded (no self-demotion or self-deactivation, at least one active
 * administrator is always kept), logged at INFO with the actor e-mail and target id only, and
 * evict the cached account status so that open sessions pick them up immediately.
 *
 * @author Rivelino Patrício
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    public static final String ERROR_SELF_CHANGE = "admin.users.error.self_change";
    public static final String ERROR_LAST_ADMIN = "admin.users.error.last_admin";

    private final ExegeseUserRepository userRepository;
    private final ExegeseSubjectRepository subjectRepository;
    private final UserSubjectPermissionRepository permissionRepository;
    private final UserAccountStatusCache accountStatusCache;
    private final String initialAdminEmail;

    public UserService(ExegeseUserRepository userRepository,
                       ExegeseSubjectRepository subjectRepository,
                       UserSubjectPermissionRepository permissionRepository,
                       UserAccountStatusCache accountStatusCache,
                       @Value("${exegese.initial-admin-email:}") String initialAdminEmail) {
        this.userRepository = userRepository;
        this.subjectRepository = subjectRepository;
        this.permissionRepository = permissionRepository;
        this.accountStatusCache = accountStatusCache;
        this.initialAdminEmail = initialAdminEmail == null ? "" : initialAdminEmail.trim();
        if (this.initialAdminEmail.isEmpty()) {
            log.warn("exegese.initial-admin-email (INITIAL_ADMIN_EMAIL) is not set: no account will be promoted "
                    + "to ROLE_ADMIN automatically. Set it to bootstrap the first administrator.");
        }
    }

    /**
     * Creates or updates the local account of a Google user. The account matching
     * {@code exegese.initial-admin-email} is promoted to ROLE_ADMIN only while no administrator exists yet.
     */
    @Transactional
    public ExegeseUser syncGoogleUser(String email, String name, String avatarUrl) {
        Optional<ExegeseUser> existingOpt = userRepository.findByEmail(email);
        boolean bootstrapAdmin = isInitialAdmin(email) && !userRepository.existsByRole(UserRole.ROLE_ADMIN);

        ExegeseUser user;
        if (existingOpt.isPresent()) {
            user = existingOpt.get();
            user.setName(name);
            user.setAvatarUrl(avatarUrl);
            if (bootstrapAdmin) {
                user.setRole(UserRole.ROLE_ADMIN);
            }
        } else {
            user = new ExegeseUser(email, name, bootstrapAdmin ? UserRole.ROLE_ADMIN : UserRole.ROLE_USER);
            user.setAvatarUrl(avatarUrl);
        }
        user.setLastLoginAt(Instant.now());
        ExegeseUser saved = userRepository.save(user);

        if (bootstrapAdmin) {
            log.info("Bootstrap: account {} promoted to ROLE_ADMIN (initial administrator)", saved.getId());
        }
        accountStatusCache.evict(saved.getEmail());
        return saved;
    }

    public boolean isInitialAdmin(String email) {
        return email != null && !initialAdminEmail.isEmpty() && initialAdminEmail.equalsIgnoreCase(email.trim());
    }

    @Transactional(readOnly = true)
    public Optional<ExegeseUser> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    @Transactional(readOnly = true)
    public Optional<ExegeseUser> findById(UUID id) {
        return userRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<ExegeseUser> findAll() {
        return userRepository.findAll();
    }

    /**
     * Changes the role of a user on behalf of an administrator.
     *
     * @param actorEmail E-mail of the administrator performing the change
     * @param id Target user id
     * @param role New role
     * @return The updated user
     * @throws AdminActionRejectedException when the admin demotes themself or the last active administrator
     */
    @Transactional
    public ExegeseUser updateRole(String actorEmail, UUID id, UserRole role) {
        ExegeseUser user = userRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("User not found with id: " + id));

        if (user.getRole() == UserRole.ROLE_ADMIN && role != UserRole.ROLE_ADMIN) {
            if (isSameAccount(actorEmail, user)) {
                throw new AdminActionRejectedException(ERROR_SELF_CHANGE);
            }
            if (user.isActive()) {
                requireAnotherActiveAdmin(user);
            }
        }

        UserRole previous = user.getRole();
        user.setRole(role);
        ExegeseUser saved = userRepository.save(user);
        accountStatusCache.evict(saved.getEmail());
        log.info("Admin action: actor={} action=update-role target={} from={} to={}", actorEmail, id, previous, role);
        return saved;
    }

    /**
     * Activates or deactivates a user on behalf of an administrator.
     *
     * @param actorEmail E-mail of the administrator performing the change
     * @param id Target user id
     * @return The updated user
     * @throws AdminActionRejectedException when the admin deactivates themself or the last active administrator
     */
    @Transactional
    public ExegeseUser toggleActive(String actorEmail, UUID id) {
        ExegeseUser user = userRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("User not found with id: " + id));

        boolean deactivating = user.isActive();
        if (deactivating) {
            if (isSameAccount(actorEmail, user)) {
                throw new AdminActionRejectedException(ERROR_SELF_CHANGE);
            }
            if (user.getRole() == UserRole.ROLE_ADMIN) {
                requireAnotherActiveAdmin(user);
            }
        }

        user.setActive(!deactivating);
        ExegeseUser saved = userRepository.save(user);
        accountStatusCache.evict(saved.getEmail());
        log.info("Admin action: actor={} action={} target={}", actorEmail, deactivating ? "deactivate" : "activate", id);
        return saved;
    }

    @Transactional
    public UserSubjectPermission grantSubjectPermission(String actorEmail, UUID userId, UUID subjectId,
                                                        SubjectPermissionLevel level) {
        ExegeseUser user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        ExegeseSubject subject = subjectRepository.findById(subjectId)
            .orElseThrow(() -> new IllegalArgumentException("Subject not found: " + subjectId));

        UserSubjectPermissionId permId = new UserSubjectPermissionId(userId, subjectId);
        UserSubjectPermission perm = permissionRepository.findById(permId)
            .orElseGet(() -> new UserSubjectPermission(user, subject, level.name()));

        perm.setPermissionLevel(level.name());
        UserSubjectPermission saved = permissionRepository.save(perm);
        log.info("Admin action: actor={} action=grant-subject-permission target={} subject={} level={}",
                actorEmail, userId, subjectId, level);
        return saved;
    }

    @Transactional
    public void revokeSubjectPermission(String actorEmail, UUID userId, UUID subjectId) {
        UserSubjectPermissionId permId = new UserSubjectPermissionId(userId, subjectId);
        if (permissionRepository.existsById(permId)) {
            permissionRepository.deleteById(permId);
            log.info("Admin action: actor={} action=revoke-subject-permission target={} subject={}",
                    actorEmail, userId, subjectId);
        }
    }

    @Transactional(readOnly = true)
    public List<UserSubjectPermission> getUserPermissions(UUID userId) {
        return permissionRepository.findByIdUserId(userId);
    }

    /**
     * Subject permissions of every user, grouped by user id, as detached DTOs safe to render in views.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<SubjectPermissionDTO>> getPermissionsByUser() {
        Map<UUID, List<SubjectPermissionDTO>> result = new LinkedHashMap<>();
        for (UserSubjectPermission perm : permissionRepository.findAllWithSubject()) {
            UUID userId = perm.getId().getUserId();
            result.computeIfAbsent(userId, (@SuppressWarnings("unused") var key) -> new ArrayList<>())
                  .add(new SubjectPermissionDTO(userId, perm.getSubject().getId(),
                          perm.getSubject().getName(), perm.getPermissionLevel()));
        }
        return result;
    }

    private static boolean isSameAccount(String actorEmail, ExegeseUser user) {
        return actorEmail != null && actorEmail.trim().equalsIgnoreCase(user.getEmail());
    }

    private void requireAnotherActiveAdmin(ExegeseUser target) {
        boolean anotherActiveAdmin = userRepository.findByRoleAndActiveTrue(UserRole.ROLE_ADMIN).stream()
                .anyMatch(admin -> !admin.getId().equals(target.getId()));
        if (!anotherActiveAdmin) {
            throw new AdminActionRejectedException(ERROR_LAST_ADMIN);
        }
    }
}
