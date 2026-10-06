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

import br.org.rivelino.exegese_ai.domain.entity.ExegeseSubject;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseUser;
import br.org.rivelino.exegese_ai.domain.entity.UserSubjectPermission;
import br.org.rivelino.exegese_ai.domain.entity.UserSubjectPermissionId;
import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseUserRepository;
import br.org.rivelino.exegese_ai.repository.UserSubjectPermissionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing user accounts, Google OAuth2 provisioning and RBAC roles.
 *
 * @author Rivelino Patrício
 */
@Service
public class UserService {

    private final ExegeseUserRepository userRepository;
    private final ExegeseSubjectRepository subjectRepository;
    private final UserSubjectPermissionRepository permissionRepository;
    private final String initialAdminEmail;

    public UserService(ExegeseUserRepository userRepository,
                       ExegeseSubjectRepository subjectRepository,
                       UserSubjectPermissionRepository permissionRepository,
                       @Value("${exegese.initial-admin-email:admin@exegese.ai}") String initialAdminEmail) {
        this.userRepository = userRepository;
        this.subjectRepository = subjectRepository;
        this.permissionRepository = permissionRepository;
        this.initialAdminEmail = initialAdminEmail;
    }

    @Transactional
    public ExegeseUser syncGoogleUser(String email, String name, String avatarUrl) {
        Optional<ExegeseUser> existingOpt = userRepository.findByEmail(email);

        if (existingOpt.isPresent()) {
            ExegeseUser user = existingOpt.get();
            user.setName(name);
            user.setAvatarUrl(avatarUrl);
            user.setLastLoginAt(Instant.now());

            if (isInitialAdmin(email)) {
                user.setRole(UserRole.ROLE_ADMIN);
            }
            return userRepository.save(user);
        }

        UserRole role = isInitialAdmin(email) ? UserRole.ROLE_ADMIN : UserRole.ROLE_USER;
        ExegeseUser newUser = new ExegeseUser(email, name, role);
        newUser.setAvatarUrl(avatarUrl);
        newUser.setLastLoginAt(Instant.now());
        return userRepository.save(newUser);
    }

    public boolean isInitialAdmin(String email) {
        return initialAdminEmail != null && initialAdminEmail.trim().equalsIgnoreCase(email.trim());
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

    @Transactional
    public ExegeseUser updateRole(UUID id, UserRole role) {
        ExegeseUser user = userRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("User not found with id: " + id));
        user.setRole(role);
        return userRepository.save(user);
    }

    @Transactional
    public ExegeseUser toggleActive(UUID id) {
        ExegeseUser user = userRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("User not found with id: " + id));
        user.setActive(!user.isActive());
        return userRepository.save(user);
    }

    @Transactional
    public UserSubjectPermission grantSubjectPermission(UUID userId, UUID subjectId, String level) {
        ExegeseUser user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        ExegeseSubject subject = subjectRepository.findById(subjectId)
            .orElseThrow(() -> new IllegalArgumentException("Subject not found: " + subjectId));

        UserSubjectPermissionId permId = new UserSubjectPermissionId(userId, subjectId);
        UserSubjectPermission perm = permissionRepository.findById(permId)
            .orElseGet(() -> new UserSubjectPermission(user, subject, level));

        perm.setPermissionLevel(level);
        return permissionRepository.save(perm);
    }

    @Transactional
    public void revokeSubjectPermission(UUID userId, UUID subjectId) {
        UserSubjectPermissionId permId = new UserSubjectPermissionId(userId, subjectId);
        permissionRepository.deleteById(permId);
    }

    @Transactional(readOnly = true)
    public List<UserSubjectPermission> getUserPermissions(UUID userId) {
        return permissionRepository.findByIdUserId(userId);
    }
}
