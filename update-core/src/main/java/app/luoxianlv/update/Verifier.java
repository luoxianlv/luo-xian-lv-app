package app.luoxianlv.update;

@FunctionalInterface
public interface Verifier {
  /** 必须验证 LXUPDATE-MANIFEST-V1 原始字节、根授权、委托用途和环境；失败抛 SecurityException。 */
  void verify(SignedDelivery delivery, byte[] rawManifest) throws SecurityException;
}
