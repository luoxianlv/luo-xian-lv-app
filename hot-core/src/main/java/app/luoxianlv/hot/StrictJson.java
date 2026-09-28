package app.luoxianlv.hot;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 启动恢复不依赖可更新的 Kotlin/Compose；此解析器只接受协议规定的无歧义 JSON。 */
public final class StrictJson {
  public static final int MAX_BYTES = 1 << 20;

  private StrictJson() {}

  public static Obj object(byte[] raw) {
    require(raw.length > 0 && raw.length <= MAX_BYTES, "JSON 大小超限");
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(raw))
              .toString();
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException("JSON 不是有效 UTF-8", error);
    }
    require(text.charAt(0) != '\ufeff', "JSON 不能带 BOM");
    Parser parser = new Parser(text);
    Object value = parser.value(0);
    parser.space();
    require(parser.position == text.length(), "JSON 包含尾随内容");
    require(value instanceof Obj, "JSON 根必须是对象");
    return (Obj) value;
  }

  public static final class Obj {
    private final Map<String, Object> fields;

    private Obj(Map<String, Object> fields) {
      this.fields = Collections.unmodifiableMap(fields);
    }

    public Obj only(String... allowed) {
      List<String> names = Arrays.asList(allowed);
      for (String name : fields.keySet()) require(names.contains(name), "JSON 包含未声明字段: " + name);
      return this;
    }

    public boolean has(String key) {
      return fields.containsKey(key);
    }

    public String string(String key) {
      Object value = fields.get(key);
      require(value instanceof String, "缺少字符串字段: " + key);
      return (String) value;
    }

    public String optionalString(String key) {
      return has(key) ? string(key) : "";
    }

    public long number(String key) {
      Object value = fields.get(key);
      require(value instanceof Long, "缺少整数字段: " + key);
      return (long) value;
    }

    public boolean bool(String key) {
      Object value = fields.get(key);
      require(value instanceof Boolean, "缺少布尔字段: " + key);
      return (boolean) value;
    }

    public Obj object(String key) {
      Object value = fields.get(key);
      require(value instanceof Obj, "缺少对象字段: " + key);
      return (Obj) value;
    }

    public List<?> array(String key) {
      Object value = fields.get(key);
      require(value instanceof List, "缺少数组字段: " + key);
      return (List<?>) value;
    }

    public List<String> strings(String key) {
      List<String> values = new ArrayList<>();
      for (Object item : array(key)) {
        require(item instanceof String, "数组元素必须是字符串: " + key);
        values.add((String) item);
      }
      return Collections.unmodifiableList(values);
    }

    public List<Obj> objects(String key) {
      List<Obj> values = new ArrayList<>();
      for (Object item : array(key)) {
        require(item instanceof Obj, "数组元素必须是对象: " + key);
        values.add((Obj) item);
      }
      return Collections.unmodifiableList(values);
    }
  }

  static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }

  private static final class Parser {
    private final String text;
    private int position;

    Parser(String text) {
      this.text = text;
    }

    void space() {
      while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) position++;
    }

    char take() {
      require(position < text.length(), "JSON 意外结束");
      return text.charAt(position++);
    }

    Object value(int depth) {
      require(depth <= 64, "JSON 嵌套超过 64 层");
      space();
      require(position < text.length(), "JSON 值缺失");
      char c = text.charAt(position);
      if (c == '"') return string();
      if (c == '{') {
        position++;
        Map<String, Object> values = new LinkedHashMap<>();
        space();
        if (position < text.length() && text.charAt(position) == '}') {
          position++;
          return new Obj(values);
        }
        while (true) {
          space();
          String key = string();
          require(!values.containsKey(key), "JSON 字段重复");
          space();
          require(take() == ':', "JSON 对象缺少冒号");
          values.put(key, value(depth + 1));
          space();
          char end = take();
          if (end == '}') return new Obj(values);
          require(end == ',', "JSON 对象缺少逗号");
        }
      }
      if (c == '[') {
        position++;
        List<Object> values = new ArrayList<>();
        space();
        if (position < text.length() && text.charAt(position) == ']') {
          position++;
          return Collections.unmodifiableList(values);
        }
        while (true) {
          values.add(value(depth + 1));
          space();
          char end = take();
          if (end == ']') return Collections.unmodifiableList(values);
          require(end == ',', "JSON 数组缺少逗号");
        }
      }
      if (text.startsWith("true", position)) {
        position += 4;
        return true;
      }
      if (text.startsWith("false", position)) {
        position += 5;
        return false;
      }
      int start = position;
      if (c == '-') position++;
      require(position < text.length() && digit(text.charAt(position)), "JSON 不支持 null 或无效值");
      if (text.charAt(position) == '0') position++;
      else while (position < text.length() && digit(text.charAt(position))) position++;
      try {
        return Long.parseLong(text.substring(start, position));
      } catch (NumberFormatException error) {
        throw new IllegalArgumentException("JSON 整数超限", error);
      }
    }

    boolean digit(char c) {
      return c >= '0' && c <= '9';
    }

    String string() {
      require(take() == '"', "JSON 需要字符串");
      StringBuilder value = new StringBuilder();
      while (true) {
        char c = take();
        if (c == '"') return value.toString();
        require(c >= 32, "JSON 字符串含未转义控制字符");
        if (c != '\\') {
          value.append(c);
          continue;
        }
        char escaped = take();
        switch (escaped) {
          case '"':
          case '\\':
          case '/':
            value.append(escaped);
            break;
          case 'b':
            value.append('\b');
            break;
          case 'f':
            value.append('\f');
            break;
          case 'n':
            value.append('\n');
            break;
          case 'r':
            value.append('\r');
            break;
          case 't':
            value.append('\t');
            break;
          case 'u':
            char unit = unicode();
            if (Character.isHighSurrogate(unit)) {
              require(take() == '\\' && take() == 'u', "Unicode 代理项未配对");
              char low = unicode();
              require(Character.isLowSurrogate(low), "Unicode 代理项未配对");
              value.append(unit).append(low);
            } else {
              require(!Character.isLowSurrogate(unit), "Unicode 代理项未配对");
              value.append(unit);
            }
            break;
          default:
            throw new IllegalArgumentException("JSON 字符串转义无效");
        }
      }
    }

    char unicode() {
      int value = 0;
      for (int i = 0; i < 4; i++) {
        char c = take();
        int digit =
            c >= '0' && c <= '9'
                ? c - '0'
                : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
        require(digit >= 0, "Unicode 转义无效");
        value = (value << 4) | digit;
      }
      return (char) value;
    }
  }
}
