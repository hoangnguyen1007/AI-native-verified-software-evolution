package com.evolution.analysis.maven;

import com.evolution.analysis.buildmodel.BuildModelResult.Reason;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.DefaultModelReader;
import org.apache.maven.model.io.ModelReader;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.DefaultHandler2;

/** Validates exact XML bytes before Maven reads them; no entities, DTDs, XInclude or external IO. */
final class SecurePomReader {
    private SecurePomReader() {}

    static Model read(byte[] bytes) throws Rejected {
        try {
            var factory = SAXParserFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var reader = factory.newSAXParser().getXMLReader();
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var guard = new DefaultHandler2() {
                private int depth;
                @Override public void startDTD(String name, String publicId, String systemId) throws SAXException {
                    throw new GuardFailure(Reason.UNSAFE_XML);
                }
                @Override public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
                    throw new GuardFailure(Reason.UNSAFE_XML);
                }
                @Override public void startElement(String uri, String local, String qName, org.xml.sax.Attributes attributes) throws SAXException {
                    if (++depth > 64) throw new GuardFailure(Reason.INPUT_LIMIT);
                    if (uri.equals("http://www.w3.org/2001/XInclude")) throw new GuardFailure(Reason.UNSAFE_XML);
                }
                @Override public void endElement(String uri, String local, String qName) { depth--; }
                @Override public void error(SAXParseException exception) throws SAXException { throw exception; }
                @Override public void fatalError(SAXParseException exception) throws SAXException { throw exception; }
            };
            reader.setContentHandler(guard);
            reader.setEntityResolver(guard);
            reader.setErrorHandler(guard);
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", guard);
            reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
            return new DefaultModelReader().read(new ByteArrayInputStream(bytes), Map.of(ModelReader.IS_STRICT, true));
        } catch (GuardFailure exception) {
            throw new Rejected(exception.reason);
        } catch (SAXException | IOException exception) {
            throw new Rejected(Reason.INVALID_XML);
        } catch (ParserConfigurationException exception) {
            throw new IllegalStateException("Secure XML reader unavailable", exception);
        }
    }

    static final class Rejected extends IOException {
        final Reason reason;
        Rejected(Reason reason) { super(reason.name()); this.reason = reason; }
    }
    private static final class GuardFailure extends SAXException {
        final Reason reason;
        GuardFailure(Reason reason) { super(reason.name()); this.reason = reason; }
    }
}
