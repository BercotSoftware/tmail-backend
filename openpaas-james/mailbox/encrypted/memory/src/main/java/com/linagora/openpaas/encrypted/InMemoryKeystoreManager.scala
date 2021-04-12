package com.linagora.openpaas.encrypted
import java.io.ByteArrayInputStream

import com.google.common.io.BaseEncoding
import com.linagora.openpaas.pgp.Encrypter
import org.apache.james.core.Username
import org.reactivestreams.Publisher
import reactor.core.scala.publisher.{SFlux, SMono}

import scala.util.Try

class InMemoryKeystoreManager (keystore: scala.collection.concurrent.Map[Username, Set[PublicKey]]) extends KeystoreManager {

  def this() {
    this(keystore = scala.collection.concurrent.TrieMap())
  }

  override def save(username: Username, payload: Array[Byte]): Publisher[KeyId] =
    computeKeyId(payload)
      .fold(e => SMono.raiseError(new IllegalArgumentException(e)), keyId => usernameExists(username)
        .fold(_ => create(username, keyId, payload), _ => update(username, keyId, payload)))

  override def listPublicKeys(username: Username): Publisher[PublicKey] =
    usernameExists(username)
      .fold(_ => SFlux.empty, _ => SFlux.fromIterable(keystore(username)))

  override def retrieveKey(username: Username, keyId: KeyId): Publisher[PublicKey] =
    usernameExists(username)
      .fold(_ => SMono.empty, _ => SMono.fromCallable[Set[PublicKey]](() => keystore(username))
        .flatMap(keys => find(keyId, keys)))

  override def delete(username: Username, keyId: KeyId): Publisher[Void] =
    usernameExists(username)
      .fold(_ => SMono.empty, _ => deleteKey(username, keyId))

  override def deleteAll(username: Username): Publisher[Void] =
    SMono.fromCallable(() => keystore.remove(username)).`then`()

  private def find(keyId: KeyId, keys: Set[PublicKey]): SMono[PublicKey] =
    keys.find(key => key.id.equals(keyId)).map(SMono.just)
      .getOrElse(SMono.raiseError(new IllegalArgumentException(s"Cannot find key $keyId")))

  private def deleteKey(username: Username, keyId: KeyId): SMono[Void] =
    SMono.fromCallable[Set[PublicKey]](() => keystore(username))
    .flatMap(keys => checkKeyId(keys, keyId)
      .fold(_ => SMono.empty, key => deleteFromSet(username, keys, key)))

  private def deleteFromSet(username: Username, keys: Set[PublicKey], key: PublicKey): SMono[Void] =
    SMono.fromCallable(() => keystore.put(username, keys -- Set(key)))
      .`then`(SMono.empty)

  private def checkKeyId(keys: Set[PublicKey], keyId: KeyId): Either[IllegalArgumentException, PublicKey] =
    keys.find(k => k.id.equals(keyId))
      .map(Right(_))
      .getOrElse(Left(new IllegalArgumentException(s"Could not find key $keyId")))

  private def create(username: Username, keyId: KeyId, payload: Array[Byte]): Publisher[KeyId] =
    SMono.fromCallable(() => keystore.put(username, Set(PublicKey(keyId, payload))))
      .map[KeyId](_ => keyId)

  private def update(username: Username, keyId: KeyId, payload: Array[Byte]): Publisher[KeyId] = {
    val oldKeys = keystore(username)
    val newkey = PublicKey(keyId, payload)
    Option.when(oldKeys.contains(newkey))(SMono.just(keyId))
      .getOrElse(insertNewKey(username, oldKeys, newkey))
  }

  private def insertNewKey(username: Username, oldKeys: Set[PublicKey], newKey: PublicKey): SMono[KeyId] =
    SMono.fromCallable(() => keystore.put(username, oldKeys ++ Set(newKey)))
      .`then`(SMono.just(newKey.id))

  private def computeKeyId(payload: Array[Byte]): Either[Throwable, KeyId] =
    Try(Encrypter.readPublicKey(new ByteArrayInputStream(payload)))
      .toEither
      .map(key => KeyId(BaseEncoding.base16().encode(key.getFingerprint)))

  private def usernameExists(username: Username): Either[Unit, Unit] =
    Option.when(keystore.contains(username))(Right())
      .getOrElse(Left())
}