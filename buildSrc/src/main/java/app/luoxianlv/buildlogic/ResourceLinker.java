package app.luoxianlv.buildlogic;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/** 只改 XML 中实际的资源引用，注释、正文、转义文字及本地定义不参与包名重写。 */
final class ResourceLinker {
  private static final String PACKAGE = "app.luoxianlv.runtime";
  private static final Pattern REFERENCE = Pattern.compile("([@?])([a-z-]+)/([A-Za-z0-9_.]+)");
  private static final Pattern CREATE_ID = Pattern.compile("@\\+id/([A-Za-z0-9_]+)");
  private final Set<String> runtime;
  private final Set<String> local = new HashSet<>();

  ResourceLinker(Set<String> runtime) {
    this.runtime = Set.copyOf(runtime);
  }

  static Document parse(String xml) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
  }

  void file(String type, String name) {
    local.add(key(type, name));
  }

  void definitions(Document document) {
    Element root = document.getDocumentElement();
    if (root.getTagName().equals("resources")) {
      for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
        if (!(child instanceof Element item) || !item.hasAttribute("name")) continue;
        String type = item.getTagName();
        if (type.equals("public") || type.equals("overlayable")) continue;
        if (type.equals("item")) type = item.getAttribute("type");
        if (type.equals("string-array") || type.equals("integer-array")) type = "array";
        if (!type.isEmpty()) file(type, item.getAttribute("name"));
        if (type.equals("declare-styleable")) {
          for (Node attr = item.getFirstChild(); attr != null; attr = attr.getNextSibling()) {
            if (attr instanceof Element value
                && value.getTagName().equals("attr")
                && (value.hasAttribute("format")
                    || value.getElementsByTagName("enum").getLength() > 0
                    || value.getElementsByTagName("flag").getLength() > 0))
              file("attr", value.getAttribute("name"));
          }
        }
      }
    }
    collectIds(root);
  }

  private void collectIds(Element element) {
    var attrs = element.getAttributes();
    for (int i = 0; i < attrs.getLength(); i++) {
      var match = CREATE_ID.matcher(attrs.item(i).getNodeValue().trim());
      if (match.matches()) file("id", match.group(1));
    }
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling())
      if (child instanceof Element nested) collectIds(nested);
  }

  String link(Document document) throws Exception {
    rewrite(document.getDocumentElement());
    var factory = TransformerFactory.newInstance();
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    var transformer = factory.newTransformer();
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    var writer = new StringWriter();
    transformer.transform(new DOMSource(document), new StreamResult(writer));
    return writer.toString();
  }

  private void rewrite(Element element) {
    if (element.getTagName().equals("style")) {
      String parent = element.getAttribute("parent");
      if (!element.hasAttribute("parent")) {
        String name = element.getAttribute("name");
        if (name.contains(".")) parent = name.substring(0, name.lastIndexOf('.'));
      }
      if (external("style", parent))
        element.setAttribute("parent", qualified('@', "style", parent));
    }
    Node parent = element.getParentNode();
    boolean styleItem =
        element.getTagName().equals("item")
            && parent instanceof Element group
            && group.getTagName().equals("style");
    boolean attrRef =
        element.getTagName().equals("attr")
            && parent instanceof Element group
            && group.getTagName().equals("declare-styleable");
    if ((styleItem || attrRef) && external("attr", element.getAttribute("name")))
      element.setAttribute("name", "*" + PACKAGE + ":" + element.getAttribute("name"));
    var attributes = element.getAttributes();
    for (int i = 0; i < attributes.getLength(); i++) {
      Node attr = attributes.item(i);
      if (!attr.getNodeName().equals("name")) attr.setNodeValue(reference(attr.getNodeValue()));
    }
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element nested) rewrite(nested);
      else if (child.getNodeType() == Node.TEXT_NODE)
        child.setNodeValue(reference(child.getNodeValue()));
    }
  }

  private String reference(String text) {
    String value = text.trim();
    var match = REFERENCE.matcher(value);
    String linked = value;
    if (match.matches() && external(match.group(2), match.group(3)))
      linked = qualified(match.group(1).charAt(0), match.group(2), match.group(3));
    else if (value.matches("\\?[A-Za-z0-9_]+") && external("attr", value.substring(1)))
      linked = qualified('?', "attr", value.substring(1));
    if (linked.equals(value)) return text;
    int at = text.indexOf(value);
    return text.substring(0, at) + linked + text.substring(at + value.length());
  }

  private boolean external(String type, String name) {
    return runtime.contains(key(type, name)) && !local.contains(key(type, name));
  }

  private static String qualified(char prefix, String type, String name) {
    return prefix + "*" + PACKAGE + ":" + type + "/" + name;
  }

  static String key(String type, String name) {
    return type + "/" + name.replace('.', '_');
  }
}
