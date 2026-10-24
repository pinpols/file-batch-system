package io.github.pinpols.batch.worker.imports.stage.format;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Parses XML payloads into NDJSON records. */
public class XmlFormatParser implements FormatParser {

  private static final String ERROR_CODE_XML_INVALID = "IMPORT_PARSE_XML_INVALID";

  private final ParseSupport support;

  public XmlFormatParser(ParseSupport support) {
    this.support = support;
  }

  @Override
  public long parse(ImportJobContext context, FormatParseRequest request, BufferedWriter writer)
      throws Exception {
    boolean preserveLogicalRow = request.preserveLogicalRow();
    if (!request.hasText()) {
      return 0L;
    }
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setNamespaceAware(true);
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document doc;
    try (BufferedReader reader = request.openTextReader()) {
      doc = builder.parse(new InputSource(reader));
    }
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
            String fieldName = localElementName(child);
            String childText = child.getTextContent();
            String trimmedChildText = EmptyChecks.isNull(childText) ? null : childText.trim();
            row.put(fieldName, trimmedChildText);
          }
        }
        support.collectSchemaFields(context, row);
        ParseSupport.ParsedRecordWriteParam writeParam =
            ParseSupport.ParsedRecordWriteParam.builder()
                .context(context)
                .writer(writer)
                .row(row)
                .preserveLogicalRow(preserveLogicalRow)
                .recordNo(recordNo)
                .errorCode(ERROR_CODE_XML_INVALID)
                .rawRecord(row)
                .build();
        support.writeParsedRecord(writeParam);
      } catch (Exception exception) {
        SwallowedExceptionLogger.warn(XmlFormatParser.class, "catch:Exception", exception);

        support.recordParseError(
            context, recordNo, ERROR_CODE_XML_INVALID, exception.getMessage(), element);
      }
    }
    return recordNo;
  }

  private String resolveXmlRecordElement(Object templateConfigObject) {
    Map<String, Object> hints = support.parseHints(templateConfigObject);
    Object v = hints.get("xmlRecordElement");
    if (EmptyChecks.isNotNull(v)) {
      String presenceText = String.valueOf(v);
      if (Texts.hasText(presenceText)) {
        String rawRecordElement = String.valueOf(v);
        return rawRecordElement.trim();
      }
    }
    if (templateConfigObject instanceof Map<?, ?> map) {
      Object direct = map.get("xml_record_element");
      if (EmptyChecks.isNotNull(direct)) {
        String presenceText = String.valueOf(direct);
        if (Texts.hasText(presenceText)) {
          String rawRecordElement = String.valueOf(direct);
          return rawRecordElement.trim();
        }
      }
    }
    return "record";
  }

  private String localElementName(Element element) {
    String local = element.getLocalName();
    if (Texts.hasText(local)) {
      return local;
    }
    String tag = element.getTagName();
    if (tag != null && tag.contains(":")) {
      int namespaceSeparatorIndex = tag.indexOf(':');
      return tag.substring(namespaceSeparatorIndex + 1);
    }
    return tag;
  }
}
