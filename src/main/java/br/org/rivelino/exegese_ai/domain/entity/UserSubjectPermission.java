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
package br.org.rivelino.exegese_ai.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;

/**
 * Granular subject-based permissions mapped to individual users.
 *
 * @author Rivelino Patrício
 */
@Entity
@Table(name = "user_subject_permission")
public class UserSubjectPermission {

    @EmbeddedId
    private UserSubjectPermissionId id = new UserSubjectPermissionId();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id", nullable = false)
    private ExegeseUser user;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("subjectId")
    @JoinColumn(name = "subject_id", nullable = false)
    private ExegeseSubject subject;

    @Column(name = "permission_level", nullable = false, length = 50)
    private String permissionLevel = "READ";

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt = Instant.now();

    public UserSubjectPermission() {
    }

    public UserSubjectPermission(ExegeseUser user, ExegeseSubject subject, String permissionLevel) {
        this.user = user;
        this.subject = subject;
        this.permissionLevel = permissionLevel;
        this.id = new UserSubjectPermissionId(user.getId(), subject.getId());
        this.grantedAt = Instant.now();
    }

    public UserSubjectPermissionId getId() {
        return id;
    }

    public void setId(UserSubjectPermissionId id) {
        this.id = id;
    }

    public ExegeseUser getUser() {
        return user;
    }

    public void setUser(ExegeseUser user) {
        this.user = user;
    }

    public ExegeseSubject getSubject() {
        return subject;
    }

    public void setSubject(ExegeseSubject subject) {
        this.subject = subject;
    }

    public String getPermissionLevel() {
        return permissionLevel;
    }

    public void setPermissionLevel(String permissionLevel) {
        this.permissionLevel = permissionLevel;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public void setGrantedAt(Instant grantedAt) {
        this.grantedAt = grantedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserSubjectPermission that = (UserSubjectPermission) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
