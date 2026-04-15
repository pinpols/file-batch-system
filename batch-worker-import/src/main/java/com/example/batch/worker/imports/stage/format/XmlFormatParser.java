package com.example.batch.worker.imports.stage.format;

import com.example.batch.worker.imports.domain.ImportJobContext;
import java.io.BufferedWriter;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Parses XML payloads into NDJSON records. */
public class XmlFormatParser implements FormatParser {

  private final ParseSupport support;

  public XmlFormatParser(ParseSupport support) {
    this.support = support;
  }

  @Override
  public long parse(ImportJobContext context, FormatParseRequest request, BufferedWriter writer)
      throws Exception {
    String payloadText = request.payloadText();
    boolean preserveLogicalRow = request.preserveLogicalRow();
    if (!StringUtils.hasText(payloadText)) {
      return 0L;
    }
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setNamespaceAware(true);
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document doc = builder.parse(new InputSource(new StringReader(payloadText)));
    String recordElement = resolveXmlRecordElement(request.templateConfig());
    NodeList nodes = doc.getElementsByTagNameNS("*", recordElement);
    if (nodes.getLength() == 0) {
      nodes = doc.getElementsByTagName(recordElement);
    }
    long recordNo = 0L;
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (!(node instanceof Element element)) {
        continue;
      }
      recordNo++;
      try {
        Map<String, String> row = new LinkedHashMap<>();
        NodeList children = element.getChildNodes();
        for (int j = 0; j < children.getLength(); j++) {
          if (children.item(j) instanceof Element child) {
            row.put(
                localElementName(child),
                child.getTextContent() == null ? null : child.getTextContent().trim());
          }
        }
        support.collectSchemaFields(context, row);
        support.writeParsedRecord(
            context,
            writer,
            row,
            preserveLogicalRow,
            recordNo,
            "IMPORT_PARSE_XML_INVALID",
            row);
      } catch (Exception exception) {
        support.recordParseError(
            context, recordNo, "IMPORT_PARSE_XML_INVALID", exception.getMessage(), element);
      }
    }
    return recordNo;
  }

  private String resolveXmlRecordElement(Object templateConfigObject) {
    Map<String, Object> hints = support.parseHints(templateConfigObject);
    Object v = hints.get("xmlRecordElement");
    if (v != null && StringUtils.hasText(String.valueOf(v))) {
      return String.valueOf(v).trim();
    }
    if (templateConfigObject instanceof Map<?, ?> map) {
      Object direct = map.get("xml_record_element");
      if (direct != null && StringUtils.hasText(String.valueOf(direct))) {
        return String.valueOf(direct).trim();
      }
    }
    return "record";
  }

  private String localElementName(Element element) {
    String local = element.getLocalName();
    if (StringUtils.hasText(local)) {
      return local;
    }
    String tag = element.getTagName();
    if (tag != null && tag.contains(":")) {
      return tag.substring(tag.indexOf(':') + 1);
    }
    return tag;
  }
}
