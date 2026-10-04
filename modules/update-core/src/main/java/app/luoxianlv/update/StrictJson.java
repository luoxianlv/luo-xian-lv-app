package app.luoxianlv.update;

import java.util.HashSet;
import java.util.Set;

/** Android JSONObject 的宽松语法不能成为签名内容的第二种解释。 */
final class StrictJson {
  private final String text;
  private int position;

  private StrictJson(String text) {
    this.text = text;
  }

  static void check(String text) {
    StrictJson parser = new StrictJson(text);
    parser.value(0);
    parser.space();
    if (parser.position != text.length()) throw new SecurityException("更新说明含尾随 JSON 数据");
  }

  private void value(int depth) {
    if (depth > 12) throw new SecurityException("更新说明嵌套过深");
    space();
    char token = peek();
    if (token == '{') {
      position++;
      space();
      Set<String> keys = new HashSet<>();
      if (take('}')) return;
      do {
        space();
        String key = string();
        if (!keys.add(key)) throw new SecurityException("更新说明 JSON 字段重复");
        space();
        need(':');
        value(depth + 1);
        space();
        if (take('}')) return;
        need(',');
      } while (true);
    } else if (token == '[') {
      position++;
      space();
      if (take(']')) return;
      do {
        value(depth + 1);
        space();
        if (take(']')) return;
        need(',');
      } while (true);
    } else if (token == '"') string();
    else if (token == 't') literal("true");
    else if (token == 'f') literal("false");
    else if (token == 'n') literal("null");
    else number();
  }

  private String string() {
    need('"');
    StringBuilder value = new StringBuilder();
    while (true) {
      char ch = peek();
      position++;
      if (ch == '"') break;
      if (ch < 32) throw new SecurityException("更新说明 JSON 字符串无效");
      if (ch == '\\') {
        char escaped = peek();
        position++;
        switch (escaped) {
          case '"':
          case '\\':
          case '/':
            ch = escaped;
            break;
          case 'b':
            ch = '\b';
            break;
          case 'f':
            ch = '\f';
            break;
          case 'n':
            ch = '\n';
            break;
          case 'r':
            ch = '\r';
            break;
          case 't':
            ch = '\t';
            break;
          case 'u':
            int code = 0;
            for (int i = 0; i < 4; i++) {
              char hex = peek();
              position++;
              int digit =
                  hex >= '0' && hex <= '9'
                      ? hex - '0'
                      : hex >= 'a' && hex <= 'f'
                          ? hex - 'a' + 10
                          : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
              if (digit < 0) throw new SecurityException("JSON Unicode 转义无效");
              code = code * 16 + digit;
            }
            ch = (char) code;
            break;
          default:
            throw new SecurityException("JSON 转义无效");
        }
      }
      value.append(ch);
    }
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (Character.isHighSurrogate(ch)) {
        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
          throw new SecurityException("JSON Unicode 字符无效");
      } else if (Character.isLowSurrogate(ch)) throw new SecurityException("JSON Unicode 字符无效");
    }
    return value.toString();
  }

  private void number() {
    take('-');
    char first = peek();
    if (first == '0') position++;
    else {
      if (first < '1' || first > '9') throw new SecurityException("JSON 数字无效");
      digits();
    }
    if (take('.')) {
      if (!digit()) throw new SecurityException("JSON 小数无效");
      digits();
    }
    if (take('e') || take('E')) {
      if (!take('+')) take('-');
      if (!digit()) throw new SecurityException("JSON 指数无效");
      digits();
    }
  }

  private void digits() {
    while (digit()) position++;
  }

  private boolean digit() {
    return position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9';
  }

  private void literal(String value) {
    if (!text.startsWith(value, position)) throw new SecurityException("JSON 值无效");
    position += value.length();
  }

  private char peek() {
    if (position >= text.length()) throw new SecurityException("JSON 数据截断");
    return text.charAt(position);
  }

  private boolean take(char ch) {
    if (position < text.length() && text.charAt(position) == ch) {
      position++;
      return true;
    }
    return false;
  }

  private void need(char ch) {
    if (!take(ch)) throw new SecurityException("更新说明 JSON 语法无效");
  }

  private void space() {
    while (position < text.length()) {
      char ch = text.charAt(position);
      if (ch != ' ' && ch != '\r' && ch != '\n' && ch != '\t') break;
      position++;
    }
  }
}
