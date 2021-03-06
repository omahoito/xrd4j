/**
 * The MIT License
 * Copyright © 2017 Population Register Centre (VRK)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package fi.vrk.xrd4j.server;

import fi.vrk.xrd4j.common.exception.XRd4JException;
import fi.vrk.xrd4j.common.message.ErrorMessage;
import fi.vrk.xrd4j.common.message.ServiceRequest;
import fi.vrk.xrd4j.common.message.ServiceResponse;
import fi.vrk.xrd4j.common.util.Constants;
import fi.vrk.xrd4j.common.util.FileUtil;
import fi.vrk.xrd4j.common.util.SOAPHelper;
import fi.vrk.xrd4j.server.deserializer.ServiceRequestDeserializer;
import fi.vrk.xrd4j.server.deserializer.ServiceRequestDeserializerImpl;
import fi.vrk.xrd4j.server.serializer.AbstractServiceResponseSerializer;
import fi.vrk.xrd4j.server.serializer.ServiceResponseSerializer;
import fi.vrk.xrd4j.server.utils.AdapterUtils;
import java.io.IOException;
import java.io.PrintWriter;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPMessage;
import javax.servlet.http.HttpServlet;
import javax.xml.soap.MimeHeaders;
import javax.xml.soap.SOAPElement;
import javax.xml.soap.SOAPEnvelope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This an abstract base class for Servlets that implement SOAP message
 * processing.
 *
 * @author Petteri Kivimäki
 */
public abstract class AbstractAdapterServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(AbstractAdapterServlet.class);
    private static final String FAULT_CODE_CLIENT = "SOAP-ENV:Client";
    private ServiceRequestDeserializer deserializer;
    private ServiceResponseSerializer serializer;
    private String errGetNotSupportedStr;
    private String errWsdlNotFoundStr;
    private String errInternalServerErrStr;
    private final ErrorMessage errGetNotSupported = new ErrorMessage(FAULT_CODE_CLIENT, "HTTP GET method not implemented", null, null);
    private final ErrorMessage errWsdlNotFound = new ErrorMessage(FAULT_CODE_CLIENT, "WSDL not found", null, null);
    private final ErrorMessage errInternalServerErr = new ErrorMessage(FAULT_CODE_CLIENT, "500 Internal Server Error", null, null);
    private final ErrorMessage errUnknownServiceCode = new ErrorMessage(FAULT_CODE_CLIENT, "Unknown service code.", null, null);

    /**
     * Handles and processes the given request and returns a SOAP message as a
     * response.
     *
     * @param request ServiceRequest to be processed
     * @return ServiceResponse that contains the SOAP response
     * @throws SOAPException if there's a SOAP error
     * @throws XRd4JException if there's a XRd4J error
     */
    protected abstract ServiceResponse handleRequest(ServiceRequest request) throws SOAPException, XRd4JException;

    /**
     * Must return the aboslute path of the WSDL file.
     *
     * @return absolute path of the WSDL file
     */
    protected abstract String getWSDLPath();

    /**
     * Initializes AbstractAdapterServlet.
     */
    @Override
    public void init() {
        logger.debug("Starting to initialize AbstractServlet.");
        this.deserializer = new ServiceRequestDeserializerImpl();
        this.serializer = new DummyServiceResponseSerializer();
        logger.debug("Initialize \"errGetNotSupportedStr\" error message.");
        this.errGetNotSupportedStr = SOAPHelper.toString(this.errorToSOAP(this.errGetNotSupported, null));
        logger.debug("Initialize \"errWsdlNotFoundStr\" error message.");
        this.errWsdlNotFoundStr = SOAPHelper.toString(this.errorToSOAP(this.errWsdlNotFound, null));
        logger.debug("Initialize \"errInternalServerErrStr\" error message.");
        this.errInternalServerErrStr = SOAPHelper.toString(this.errorToSOAP(this.errInternalServerErr, null));
        logger.debug("AbstractServlet initialized.");
    }

    /**
     * Handles the HTTP <code>POST</code> method.
     *
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String errString = "Invalid SOAP message.";
        logger.debug("New request received.");
        SOAPMessage soapRequest = null;
        SOAPMessage soapResponse = null;

        // Log HTTP headers if debug is enabled
        if (logger.isDebugEnabled()) {
            logger.debug(AdapterUtils.getHeaderInfo(request));
            logger.debug("Request content length {} bytes.", request.getContentLength());
        }

        // Get incoming SOAP message
        if (request.getContentType().toLowerCase().startsWith(Constants.TEXT_XML)) {
            // Regular SOAP message without attachments
            logger.info("Request's content type is \"{}\".", Constants.TEXT_XML);
            soapRequest = SOAPHelper.toSOAP(request.getInputStream());
        } else if (request.getContentType().toLowerCase().startsWith(Constants.MULTIPART_RELATED)) {
            // SOAP message with attachments
            logger.info("Request's content type is \"{}\".", Constants.MULTIPART_RELATED);
            MimeHeaders mh = AdapterUtils.getHeaders(request);
            soapRequest = SOAPHelper.toSOAP(request.getInputStream(), mh);
            logger.trace(AdapterUtils.getAttachmentsInfo(soapRequest));
        } else {
            // Invalid content type -> message is not processed
            logger.warn("Invalid content type : \"{}\".", request.getContentType());
            errString = "Invalid content type : \"" + request.getContentType() + "\".";
        }

        // Conversion has failed if soapRequest is null. Return SOAP Fault.
        if (soapRequest == null) {
            logger.warn("Unable to deserialize the request to SOAP. SOAP Fault is returned.");
            logger.trace("Incoming message : \"{}\"", request.getInputStream().toString());
            ErrorMessage errorMessage = new ErrorMessage(FAULT_CODE_CLIENT, errString, "", "");
            soapResponse = this.errorToSOAP(errorMessage, null);
        }

        // Deserialize incoming SOAP message to ServiceRequest object
        if (soapResponse == null) {
            // Convert SOAP request to servive request
            ServiceRequest serviceRequest = this.fromSOAPToServiceRequest(soapRequest);
            // If conversion fails, return SOAP fault
            if (serviceRequest == null) {
                ErrorMessage errorMessage = new ErrorMessage(FAULT_CODE_CLIENT, "Invalid X-Road SOAP message. Unable to parse the request.", "", "");
                soapResponse = this.errorToSOAP(errorMessage, null);
            }

            // Process ServiceRequest object
            if (soapResponse == null) {
                // Process request and generate SOAP response
                soapResponse = this.processServiceRequest(serviceRequest);
            }
        }
        // Write the SOAP response to output stream
        writeResponse(soapResponse, response);
    }

    /**
     * Writes the given SOAP response to output stream. Sets the necessary HTTP
     * headers according to the content of the response.
     *
     * @param soapResponse SOAP response
     * @param response servlet response
     */
    private void writeResponse(SOAPMessage soapResponse, HttpServletResponse response) {
        PrintWriter out = null;
        try {
            logger.debug("Send response.");
            // SOAPMessage to String
            String responseStr = SOAPHelper.toString(soapResponse);
            // Set response headers
            if (responseStr != null && soapResponse != null && soapResponse.getAttachments().hasNext()) {
                // Get MIME boundary from SOAP message
                String boundary = AdapterUtils.getMIMEBoundary(responseStr);
                response.setContentType(Constants.MULTIPART_RELATED + "; type=\"text/xml\"; boundary=\"" + boundary + "\"; charset=UTF-8");
            } else {
                response.setContentType(Constants.TEXT_XML + "; charset=UTF-8");
            }
            logger.debug("Response content type : \"{}\".", response.getContentType());
            // Get writer
            out = response.getWriter();
            // Send response
            if (responseStr != null) {
                out.println(responseStr);
                logger.trace("SOAP response : \"{}\"", responseStr);
            } else {
                out.println(this.errInternalServerErrStr);
                logger.warn("Internal serveri error. Message processing failed.");
                logger.trace("SOAP response : \"{}\"", this.errInternalServerErrStr);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            if (out != null) {
                out.println(this.errInternalServerErrStr);
            }
        } finally {
            if (out != null) {
                out.close();
            }
            logger.debug("Request was succesfully processed.");
        }
    }

    /**
     * Converts the give SOAPMessage to ServiceRequest object.
     *
     * @param soapRequest SOAPMessage to be converted
     * @return ServiceRequest or null
     */
    private ServiceRequest fromSOAPToServiceRequest(SOAPMessage soapRequest) {
        logger.trace("Incoming SOAP message : \"{}\"", SOAPHelper.toString(soapRequest));
        ServiceRequest serviceRequest = null;
        try {
            // Try to deserialize SOAP Message to ServiceRequest
            serviceRequest = deserializer.deserialize(soapRequest);
            logger.debug("SOAP message header was succesfully deserialized to ServiceRequest.");
        } catch (Exception ex) {
            // If deserializing SOAP Message fails, return SOAP Fault
            logger.error("Deserializing SOAP message header to ServiceRequest failed. Return SOAP Fault.");
            logger.error(ex.getMessage(), ex);
        }
        return serviceRequest;
    }

    /**
     * Processes the given ServiceRequest object and generates SOAPMessage
     * object that's used as a response.
     *
     * @param serviceRequest ServiceRequest object to be processed
     * @return SOAPMessage representing the service response
     */
    private SOAPMessage processServiceRequest(ServiceRequest serviceRequest) {
        try {
            // Process application specific requests
            logger.debug("Process ServiceRequest.");
            ServiceResponse serviceResponse = this.handleRequest(serviceRequest);
            if (serviceResponse == null) {
                logger.warn("ServiceRequest was not processed. Unknown service code.");
                return this.errorToSOAP(this.errUnknownServiceCode, null);
            } else {
                SOAPMessage soapResponse = serviceResponse.getSoapMessage();
                logger.debug("ServiceRequest was processed succesfully.");
                return soapResponse;
            }
        } catch (XRd4JException ex) {
            logger.error(ex.getMessage(), ex);
            if (serviceRequest.hasError()) {
                return this.errorToSOAP(this.cloneErrorMessage(serviceRequest.getErrorMessage()), null);
            } else {
                return this.errorToSOAP(this.errInternalServerErr, null);
            }
        } catch (SOAPException | NullPointerException ex) {
            logger.error(ex.getMessage(), ex);
            return this.errorToSOAP(this.errInternalServerErr, null);
        }
    }

    /**
     * Handles the HTTP <code>GET</code> method.
     *
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setContentType(Constants.TEXT_XML + ";charset=UTF-8");
        PrintWriter out = response.getWriter();
        try {
            if (request.getParameter("wsdl") != null) {
                logger.debug("WSDL file request received.");
                String path = this.getWSDLPath();
                // If only filename is given, absolute path must be added
                if (path.matches("^[-_.A-Za-z0-9]+$")) {
                    path = this.getServletContext().getRealPath("/WEB-INF/classes/") + "/" + path;
                    logger.debug("Only filename was given. Absolute path is : \"{}\".", path);
                }
                // Read WSDL file
                String wsdl = FileUtil.read(path);
                if (!wsdl.isEmpty()) {
                    out.println(wsdl);
                    logger.trace("WSDL file was found and returned to the requester.");
                } else {
                    out.println(this.errWsdlNotFoundStr);
                    logger.warn("WSDL file was not found. SOAP Fault was returned.");
                }
                logger.debug("WSDL file request processed.");
            } else {
                logger.warn("New GET request received. Not supported. SOAP Fault is returned.");
                out.println(errGetNotSupportedStr);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            out.println(this.errInternalServerErrStr);
        } finally {
            out.close();
        }
    }

    /**
     * Converts the given ErrorMessage to standard SOAP Fault message or
     * non-technical SOAP error message based on the type of the given error.
     *
     * @param error ErrorMessage object that contains the error details
     * @param serviceRequest ServiceRequest object related to the error
     * @return SOAPMessage object containing ErrorMessage details
     */
    protected SOAPMessage errorToSOAP(ErrorMessage error, ServiceRequest serviceRequest) {
        ServiceResponse serviceResponse = new ServiceResponse();
        serviceResponse.setErrorMessage(error);
        return this.serializer.serialize(serviceResponse, serviceRequest);
    }

    /**
     * Clones the given error message by using the constructor with four
     * arguments. In this way it's sure that the error's type is
     * "STANDARD_SOAP_ERROR_MESSAGE".
     *
     * @param errorMsg ErrorMessage object to be cloned
     * @return new ErrorMessage object
     */
    private ErrorMessage cloneErrorMessage(ErrorMessage errorMsg) {
        return new ErrorMessage(errorMsg.getFaultCode(), errorMsg.getFaultString(), errorMsg.getFaultActor(), errorMsg.getDetail());
    }

    /**
     * This is a dummy implementation of the AbstractServiceResponseSerializer
     * class. It's needed only for generating SOAP Fault messages.
     * SerializeResponse method gets never called.
     */
    private class DummyServiceResponseSerializer extends AbstractServiceResponseSerializer {

        @Override
        public void serializeResponse(ServiceResponse response, SOAPElement soapResponse, SOAPEnvelope envelope) throws SOAPException {
            /**
             * This is needed only for generating SOAP Fault messages.
             * SerializeResponse method gets never called.
             */
        }
    }
}
