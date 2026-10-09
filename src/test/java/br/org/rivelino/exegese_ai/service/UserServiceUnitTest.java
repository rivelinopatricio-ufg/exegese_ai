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

import br.org.rivelino.exegese_ai.domain.entity.ExegeseUser;
import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseUserRepository;
import br.org.rivelino.exegese_ai.repository.UserSubjectPermissionRepository;
import br.org.rivelino.exegese_ai.security.UserAccountStatusCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserService} validating user queries, account protection rules, and identity checks.
 *
 * @author Rivelino Patrício
 */
class UserServiceUnitTest {

    @Mock
    private ExegeseUserRepository userRepository;

    @Mock
    private ExegeseSubjectRepository subjectRepository;

    @Mock
    private UserSubjectPermissionRepository permissionRepository;

    @Mock
    private UserAccountStatusCache accountStatusCache;

    private UserService userService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        userService = new UserService(userRepository, subjectRepository, permissionRepository, accountStatusCache, "admin@initial.com");
    }

    @Test
    @DisplayName("findById and findByGoogleSub delegate to user repository")
    void testLookupMethods() {
        UUID id = UUID.randomUUID();
        ExegeseUser user = new ExegeseUser("user@test.com", "User", UserRole.ROLE_USER);
        user.setId(id);
        user.setGoogleSub("sub123");

        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userRepository.findByGoogleSub("sub123")).thenReturn(Optional.of(user));

        assertThat(userService.findById(id)).contains(user);
        assertThat(userService.findByGoogleSub("sub123")).contains(user);
    }

    @Test
    @DisplayName("syncGoogleUser updates name and picture of existing user")
    void testSyncGoogleUserUpdate() {
        ExegeseUser user = new ExegeseUser("user@test.com", "Old Name", UserRole.ROLE_USER);
        user.setGoogleSub("sub123");
        user.setAvatarUrl("old.png");
        when(userRepository.findByGoogleSub("sub123")).thenReturn(Optional.of(user));
        when(userRepository.save(any(ExegeseUser.class))).thenReturn(user);

        ExegeseUser synced = userService.syncGoogleUser("user@test.com", "New Name", "new.png", "sub123");

        assertThat(synced.getName()).isEqualTo("New Name");
        assertThat(synced.getAvatarUrl()).isEqualTo("new.png");
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("syncGoogleUser throws GoogleIdentityMismatchException when email is bound to another sub")
    void testSyncGoogleUserMismatch() {
        ExegeseUser user = new ExegeseUser("user@test.com", "Name", UserRole.ROLE_USER);
        user.setGoogleSub("otherSub");
        when(userRepository.findByGoogleSub("sub123")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.syncGoogleUser("user@test.com", "Name", null, "sub123"))
                .isInstanceOf(GoogleIdentityMismatchException.class);
    }

    @Test
    @DisplayName("toggleActive prevents disabling the last active admin")
    void testToggleActivePreventsDisablingLastAdmin() {
        UUID adminId = UUID.randomUUID();
        ExegeseUser admin = new ExegeseUser("admin@test.com", "Admin", UserRole.ROLE_ADMIN);
        admin.setId(adminId);
        admin.setGoogleSub("subAdmin");
        admin.setActive(true);

        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(userRepository.findByEmail("other-admin@test.com")).thenReturn(Optional.of(new ExegeseUser("other-admin@test.com", "Other", UserRole.ROLE_ADMIN)));
        when(userRepository.findByRoleAndActiveTrue(UserRole.ROLE_ADMIN)).thenReturn(List.of(admin));

        assertThatThrownBy(() -> userService.toggleActive("other-admin@test.com", adminId))
                .isInstanceOf(AdminActionRejectedException.class);
    }

    @Test
    @DisplayName("toggleActive throws IllegalArgumentException when target user is not found")
    void testToggleActiveUserNotFound() {
        UUID missingId = UUID.randomUUID();
        when(userRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.toggleActive("actor@test.com", missingId))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
