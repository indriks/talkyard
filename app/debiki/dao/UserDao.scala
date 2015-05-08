/**
 * Copyright (C) 2012 Kaj Magnus Lindberg (born 1979)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package debiki.dao

import com.debiki.core._
import debiki.DebikiHttp.throwNotFound
import java.{util => ju}
import requests.ApiRequest
import scala.collection.immutable
import Prelude._
import EmailNotfPrefs.EmailNotfPrefs
import CachingDao.{CacheKey, CacheValueIgnoreVersion}


trait UserDao {
  self: SiteDao =>


  def approveUser(userId: UserId, approverId: UserId) {
    approveRejectUndoUser(userId, approverId = approverId, isapproved = Some(true))
  }


  def rejectUser(userId: UserId, approverId: UserId) {
    approveRejectUndoUser(userId, approverId = approverId, isapproved = Some(false))
  }


  def undoApproveOrRejectUser(userId: UserId, approverId: UserId) {
    approveRejectUndoUser(userId, approverId = approverId, isapproved = None)
  }


  private def approveRejectUndoUser(userId: UserId, approverId: UserId,
        isapproved: Option[Boolean]) {
    readWriteTransaction { transaction =>
      var user = transaction.loadTheCompleteUser(userId)
      user = user.copy(
        isApproved = isapproved,
        approvedAt = Some(transaction.currentTime),
        approvedById = Some(approverId))
      transaction.updateCompleteUser(user)
    }
    refreshUserInAnyCache(userId)
  }


  def createIdentityUserAndLogin(newUserData: NewUserData): LoginGrant = {
    readWriteTransaction { transaction =>
      val userId = transaction.nextAuthenticatedUserId
      val user = newUserData.makeUser(userId, transaction.currentTime)
      val identityId = transaction.nextIdentityId
      val identity = newUserData.makeIdentity(userId = userId, identityId = identityId)
      transaction.insertAuthenticatedUser(user)
      transaction.insertIdentity(identity)
      LoginGrant(Some(identity), user, isNewIdentity = true, isNewRole = true)
    }
  }


  def createPasswordUser(userData: NewPasswordUserData): User = {
    readWriteTransaction { transaction =>
      val userId = transaction.nextAuthenticatedUserId
      val user = userData.makeUser(userId, transaction.currentTime)
      transaction.insertAuthenticatedUser(user)
      user
    }
  }


  def changePassword(userId: UserId, newPasswordSaltHash: String): Boolean = {
    readWriteTransaction { transaction =>
      var user = transaction.loadTheCompleteUser(userId)
      user = user.copy(passwordHash = Some(newPasswordSaltHash))
      transaction.updateCompleteUser(user)
    }
  }


  def loginAsGuest(loginAttempt: GuestLoginAttempt): User = {
    readWriteTransaction { transaction =>
      transaction.loginAsGuest(loginAttempt).user
    }
  }


  def tryLogin(loginAttempt: LoginAttempt): LoginGrant =
    siteDbDao.tryLogin(loginAttempt)


  def loadUsers(): immutable.Seq[User] = {
    readOnlyTransaction { transaction =>
      transaction.loadUsers()
    }
  }


  def loadCompleteUsers(onlyThosePendingApproval: Boolean): immutable.Seq[CompleteUser] = {
    readOnlyTransaction { transaction =>
      transaction.loadCompleteUsers(onlyThosePendingApproval = onlyThosePendingApproval)
    }
  }


  def loadCompleteUser(userId: UserId): Option[CompleteUser] = {
    readOnlyTransaction { transaction =>
      transaction.loadCompleteUser(userId)
    }
  }


  def loadUser(userId: UserId): Option[User] =
    siteDbDao.loadUser(userId)


  def loadUserByEmailOrUsername(emailOrUsername: String): Option[User] =
    // Don't need to cache this? Only called when logging in.
    siteDbDao.loadUserByEmailOrUsername(emailOrUsername)


  def loadUserAndAnyIdentity(userId: UserId): Option[(Option[Identity], User)] = {
    loadIdtyDetailsAndUser(userId) match {
      case Some((identity, user)) => Some((Some(identity), user))
      case None =>
        // No OAuth or OpenID identity, try load password user:
        loadUser(userId) match {
          case Some(user) =>
            Some((None, user))
          case None =>
            None
        }
    }
  }


  private def loadIdtyDetailsAndUser(userId: UserId): Option[(Identity, User)] =
    // Don't cache this, because this function is rarely called
    // — currently only when creating new website.
    siteDbDao.loadIdtyDetailsAndUser(userId)


  def loadUserInfoAndStats(userId: UserId): Option[UserInfoAndStats] =
    siteDbDao.loadUserInfoAndStats(userId)


  def listUserActions(userId: UserId): Seq[UserActionInfo] =
    siteDbDao.listUserActions(userId)


  def loadPermsOnPage(reqInfo: PermsOnPageQuery): PermsOnPage =
    // Currently this results in no database request; there's nothing to cache.
    siteDbDao.loadPermsOnPage(reqInfo)


  def loadPermsOnPage(request: ApiRequest[_], pageId: PageId): PermsOnPage = {
    val pageMeta = loadPageMeta(pageId)
    val pagePath = lookupPagePath(pageId) getOrElse throwNotFound(
      "DwE74BK0", s"No page path found to page id: $pageId")

    siteDbDao.loadPermsOnPage(PermsOnPageQuery(
      tenantId = request.tenantId,
      ip = request.ip,
      user = request.user,
      pagePath = pagePath,
      pageMeta = pageMeta))
  }


  def verifyEmail(userId: UserId, verifiedAt: ju.Date) {
    readWriteTransaction { transaction =>
      var user = transaction.loadTheCompleteUser(userId)
      user = user.copy(emailVerifiedAt = Some(verifiedAt))
      transaction.updateCompleteUser(user)
    }
    refreshUserInAnyCache(userId)
  }


  def configRole(userId: RoleId,
        emailNotfPrefs: Option[EmailNotfPrefs] = None, isAdmin: Option[Boolean] = None,
        isOwner: Option[Boolean] = None) {
    // Don't specify emailVerifiedAt here — use verifyEmail() instead; it refreshes the cache.
    readWriteTransaction { transaction =>
      var user = transaction.loadTheCompleteUser(userId)
      emailNotfPrefs foreach { prefs =>
        user = user.copy(emailNotfPrefs = prefs)
      }
      isAdmin foreach { isAdmin =>
        user = user.copy(isAdmin = isAdmin)
      }
      isOwner foreach { isOwner =>
        user = user.copy(isOwner = isOwner)
      }
      transaction.updateCompleteUser(user)
    }
    refreshUserInAnyCache(userId)
  }


  def configIdtySimple(ctime: ju.Date, emailAddr: String, emailNotfPrefs: EmailNotfPrefs) = {
    siteDbDao.configIdtySimple(ctime = ctime,
      emailAddr = emailAddr, emailNotfPrefs = emailNotfPrefs)
    // COULD refresh guest in cache: new email prefs --> perhaps show "??" not "?" after name.
  }


  def listUsers(): Seq[User] = {
    readOnlyTransaction { transaction =>
      transaction.loadUsers()
    }
  }


  def listUsernames(pageId: PageId, prefix: String): Seq[NameAndUsername] =
    siteDbDao.listUsernames(pageId = pageId, prefix = prefix)


  def loadUserIdsWatchingPage(pageId: PageId): Seq[UserId] =
    siteDbDao.loadUserIdsWatchingPage(pageId)


  def loadRolePageSettings(roleId: RoleId, pageId: PageId): RolePageSettings =
    siteDbDao.loadRolePageSettings(roleId = roleId, pageId = pageId) getOrElse
      RolePageSettings.Default


  def saveRolePageSettings(roleId: RoleId, pageId: PageId, settings: RolePageSettings) =
    siteDbDao.saveRolePageSettings(roleId = roleId, pageId = pageId, settings)


  def saveRolePreferences(preferences: UserPreferences) = {
    // BUG: the lost update bug.
    readWriteTransaction { transaction =>
      var user = transaction.loadTheCompleteUser(preferences.userId)
      user = user.copyWithNewPreferences(preferences)
      transaction.updateCompleteUser(user)
    }
    refreshUserInAnyCache(preferences.userId)
  }


  def refreshUserInAnyCache(userId: UserId) {
  }

}



trait CachingUserDao extends UserDao {
  self: CachingSiteDao =>


  override def createIdentityUserAndLogin(newUserData: NewUserData): LoginGrant = {
    val loginGrant = super.createIdentityUserAndLogin(newUserData)
    fireUserCreated(loginGrant.user)
    loginGrant
  }


  override def createPasswordUser(userData: NewPasswordUserData): User = {
    val user = super.createPasswordUser(userData)
    fireUserCreated(user)
    user
  }


  override def loginAsGuest(loginAttempt: GuestLoginAttempt): User = {
    val user = super.loginAsGuest(loginAttempt)
    putInCache(
      key(user.id),
      CacheValueIgnoreVersion(user))
    user
  }


  override def tryLogin(loginAttempt: LoginAttempt): LoginGrant = {
    // Don't save any site cache version, because user specific data doesn't change
    // when site specific data changes.
    val loginGrant = super.tryLogin(loginAttempt)
    putInCache(
      key(loginGrant.user.id),
      CacheValueIgnoreVersion(loginGrant.user))
    loginGrant
  }


  override def loadUser(userId: UserId): Option[User] = {
    lookupInCache[User](
      key(userId),
      orCacheAndReturn = super.loadUser(userId),
      ignoreSiteCacheVersion = true)
  }


  override def refreshUserInAnyCache(userId: UserId) {
    removeFromCache(key(userId))
  }


  private def key(userId: UserId) = CacheKey(siteId, s"$userId|UserById")

}


