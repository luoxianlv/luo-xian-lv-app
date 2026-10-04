package app.luoxianlv.update;

import org.json.JSONObject;

/** 只承载原始签名封装，内容验签由宿主独立的普通更新信任域完成。 */
public final class SignedDelivery {
  public final int schema;
  public final String manifest, signature, trust, trustSignature;
  public SignedDelivery(int schema, String manifest, String signature, String trust, String trustSignature) {
    this.schema = schema; this.manifest = manifest; this.signature = signature;
    this.trust = trust; this.trustSignature = trustSignature;
  }
  public static SignedDelivery fromJson(JSONObject object) throws org.json.JSONException {
    return new SignedDelivery(object.getInt("schema"), object.getString("manifest"),
        object.getString("signature"), object.getString("trust"), object.getString("trustSignature"));
  }
  public JSONObject toJson() throws org.json.JSONException {
    return new JSONObject().put("schema", schema).put("manifest", manifest).put("signature", signature)
        .put("trust", trust).put("trustSignature", trustSignature);
  }
}
