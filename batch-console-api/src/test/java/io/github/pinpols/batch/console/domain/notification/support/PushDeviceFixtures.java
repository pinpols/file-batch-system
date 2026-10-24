package io.github.pinpols.batch.console.domain.notification.support;

import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushSubscriptionEntity;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Valid device keys for sender tests; no external provider is contacted. */
public final class PushDeviceFixtures {
  private PushDeviceFixtures() {}

  public static ConsolePushSubscriptionEntity device(String tenant,String user,String endpoint) throws Exception {
    return device(tenant,user,endpoint,keyPair());
  }

  public static KeyPair keyPair() throws Exception {
    if(Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)==null) Security.addProvider(new BouncyCastleProvider());
    var generator=KeyPairGenerator.getInstance("ECDH",BouncyCastleProvider.PROVIDER_NAME);
    generator.initialize(new ECGenParameterSpec("prime256v1"));
    return generator.generateKeyPair();
  }

  public static ConsolePushSubscriptionEntity device(String tenant,String user,String endpoint,KeyPair keys) {
    var publicKey=(ECPublicKey)keys.getPublic();
    var sub=new ConsolePushSubscriptionEntity();sub.setTenantId(tenant);sub.setUsername(user);sub.setEndpoint(endpoint);
    sub.setP256dhKey(Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.getQ().getEncoded(false)));
    sub.setAuthSecret(Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]));
    return sub;
  }
}
