# XRd4J

XRd4J is a Java library for building X-Road v6 Adapter Servers and clients. The library implements X-Road v6 [SOAP profile](https://confluence.csc.fi/download/attachments/47580926/xroad_profile_of_soap_messages_0%205.pdf?version=1&modificationDate=1415865090158&api=v2). The library takes care of serialization and deserialization of SOAP messages: built-in support for standard X-Road SOAP headers, only processing of application specific ```request``` and ```response``` elements must be implemented.

##### Modules:

* ```client``` : SOAP client that generates X-Road v6 SOAP messages that can be sent to X-Road Security Server. Includes request serializer and response deserializer.
* ```server``` : Provides abstract servlet that can be use as a base class for Adapter Server implementation. Includes request deserializer and response serializer.
* ```common``` : General purpose utilities for processing SOAP messages and X-Road v6 message data models.
* ```rest``` : HTTP clients that can be used for sending requests to web services from Adapter Server.

### Maven Repository

#### Releases

Available in CSC's Maven Repository: https://maven.csc.fi/repository/internal/

Specify CSC's Maven Repository in a POM:

```
<repository>
  <id>csc-repo</id>
  <name>CSC's Maven repository</name>
  <url>https://maven.csc.fi/repository/internal/</url>
</repository>
```

#### Dependency Declaration

Declare the following depencies in a POM:

```
<!-- Module: common-->
<dependency>
  <groupId>com.pkrete.xrd4j</groupId>
  <artifactId>common</artifactId>
  <version>0.0.2</version>
</dependency>

<!-- Module: client-->
<dependency>
  <groupId>com.pkrete.xrd4j</groupId>
  <artifactId>client</artifactId>
  <version>0.0.2</version>
</dependency>

<!-- Module: server-->
<dependency>
  <groupId>com.pkrete.xrd4j</groupId>
  <artifactId>server</artifactId>
  <version>0.0.2</version>
</dependency>

<!-- Module: rest-->
<dependency>
  <groupId>com.pkrete.xrd4j</groupId>
  <artifactId>rest</artifactId>
  <version>0.0.2</version>
</dependency>
```

### Documentation

The most essential classes of the library are:

* ```ConsumerMember``` : represents X-Road consumer member that acts as a client that initiates service call by sending a ServiceRequest.
* ```ProducerMember``` : represents X-Road producer member that produces services to X-Road.
* ```ServiceRequest<?>``` : represents X-Road service request that is sent by a ConsumerMember and received by a ProviderMember. Contains the SOAP request that is sent.
* ```ServiceResponse<?, ?>``` : represents X-Road service response message that is sent by a ProviderMember and received by a ConsumerMember. Contains the SOAP response.
* ```AbstractServiceRequestSerializer``` : abstract base class for service request serializers.
* ```AbstractResponseDeserializer<?, ?>``` : abstract base class for service response deserializers.
* ```SOAPClient``` : SOAP client that offers two methods that can be used for sending SOAPMessage objects and ServiceRequest objects.
* ```AbstractAdapterServlet``` : abstract base class for Servlets that implement SOAP message processing. Can be used as a base class for Adapter Server implementations.

Setting up development environment is explained in [wiki](https://github.com/petkivim/xrd4j/wiki/Setting-up-Development-Environment).
##### Client

Client application must implement two classes:

* ```request serializer``` is responsible for converting the object representing the request payload to SOAP
  * extends ```AbstractServiceRequestSerializer```
    * serializes all the other parts of the SOAP message except ```request``` element
  * used through ```ServiceRequestSerializer``` interface
* ```response deserializer``` parses the incoming SOAP response message and constructs the objects representing the response payload
  * extends ```AbstractResponseDeserializer<?, ?>```
    * deserializes all the other parts of the incoming SOAP message except ```request``` and ```response``` elements
  * used through ```ServiceResponseSerializer``` interface
  * type of the request and response data must be given as type parameters

**N.B.** If HTTPS is used between the client and the Security Server, the public key certificate of the Security Server MUST be imported into "cacerts" keystore. Detailed [instructions](https://github.com/petkivim/xrd4j/wiki/Import-a-Certificate-as-a-Trusted-Certificate) can be found from wiki.

Main class (generated [request](examples/request1.xml), received [response](examples/response1.xml)):

```
  // Security server URL 
  // N.B. If you want to use HTTPS, the public key certificate of the Security Server
  // MUST be imported into "cacerts" keystore
  String url = "http://security.server.com/cgi-bin/consumer_proxy";
  
  // Consumer that is calling a service
  ConsumerMember consumer = new ConsumerMember("FI_TEST", "GOV", "1234567-8", "TestSystem");
  
  // Producer providing the service
  ProducerMember producer = new ProducerMember("FI_TEST", "GOV", "9876543-1", "DemoService", "helloService", "v1");
  producer.setNamespacePrefix("ts");
  producer.setNamespaceUrl("http://test.x-road.fi/producer");
  
  // Create a new ServiceRequest object, unique message id is generated by MessageHelper.
  // Type of the ServiceRequest is the type of the request data (String in this case)
  ServiceRequest<String> request = new ServiceRequest<String>(consumer, producer, MessageHelper.generateId());
  
  // Set username
  request.setUserId("jdoe");
  
  // Set request data
  request.setRequestData("Test message");

  // Application specific class that serializes request data
  ServiceRequestSerializer serializer = new HelloServiceRequestSerializer();
  
  // Application specific class that deserializes response data
  ServiceResponseDeserializer deserializer = new HelloServiceResponseDeserializer();

  // Create a new SOAP client
  SOAPClient client = new SOAPClientImpl();

  // Send the ServiceRequest, result is returned as ServiceResponse object
  ServiceResponse<String, String> serviceResponse = client.send(request, url, serializer, deserializer);

  // Print out the SOAP message received as response
  System.out.println(SOAPHelper.toString(serviceResponse.getSoapMessage()));

  // Print out only response data. In this case response data is a String.
  System.out.println(serviceResponse.getResponseData());
  
  // Check if response contains an error and print it out
  if (serviceResponse.hasError()) {
    System.out.println(serviceResponse.getErrorMessage().getErrorMessageType());
    System.out.println(serviceResponse.getErrorMessage().getFaultCode());
    System.out.println(serviceResponse.getErrorMessage().getFaultString());
    System.out.println(serviceResponse.getErrorMessage().getFaultActor());
  }
```

HelloServiceRequestSerializer (serialized [request](examples/request1.xml)):
```
  /**
   * This class is responsible for serialiazing request data to SOAP. Request data is wrapped
   * inside "request" element in SOAP body.
   */
  public class HelloServiceRequestSerializer extends AbstractServiceRequestSerializer {
  
    @Override
	/**
	 * Serializes the request data.
     * @param request ServiceRequest holding the application specific request object
     * @param soapRequest SOAPMessage's request object where the request element is added
     * @param envelope SOAPMessage's SOAPEnvelope object
	 */
    protected void serializeRequest(ServiceRequest request, SOAPElement soapRequest, SOAPEnvelope envelope) throws SOAPException {
	  // Create element "name" and put request data inside the element
      SOAPElement data = soapRequest.addChildElement(envelope.createName("name"));
      data.addTextNode((String) request.getRequestData());
    }
  }
```
HelloServiceRequestSerializer generates ```name``` element and sets request data ("Test message") as its value.

```
  <ts:request>
    <ts:name>Test message</ts:name>
  </ts:request>
```
HelloServiceResponseDeserializer ([response](examples/response1.xml) to be deserialized):
```
  /**
   * This class is responsible for deserializing "request" and "response" elements of the SOAP response message
   * returned by the Security Server. The type declaration "<String, String>" defines the type of request and
   * response data, in this case they're both String. 
   */
  public class HelloServiceResponseDeserializer extends AbstractResponseDeserializer<String, String> {

    @Override
	/**
	 * Deserializes the "request" element.
	 * @param requestNode request element
	 * @return content of the request element
	 */
    protected String deserializeRequestData(Node requestNode) throws SOAPException {
      // Loop through all the children of the "request" element
      for (int i = 0; i < requestNode.getChildNodes().getLength(); i++) {
	    // We're looking for "name" element
        if (requestNode.getChildNodes().item(i).getLocalName().equals("name")) {
	      // Return the text content of the element
          return requestNode.getChildNodes().item(i).getTextContent();
        }
      }
	  // No "name" element was found, return null
      return null;
    }

    @Override
	/**
	 * Deserializes the "response" element.
	 * @param responseNode response element
	 * @param message SOAP response
	 * @return content of the response element
	 */
    protected String deserializeResponseData(Node responseNode, SOAPMessage message) throws SOAPException {
      // Loop through all the children of the "response" element
      for (int i = 0; i < responseNode.getChildNodes().getLength(); i++) {
	    // We're looking for "message" element
        if (responseNode.getChildNodes().item(i).getLocalName().equals("message")) {
	      // Return the text content of the element
          return responseNode.getChildNodes().item(i).getTextContent();
        }
      }
	  // No "message" element was found, return null
      return null;
    }
}
```

HelloServiceResponseDeserializer's ```deserializeRequestData``` method reads ```name``` elements's value ("Test message") under ```request``` element, and ```deserializeResponseData``` method reads ```message``` element's value ("Hello Test message! Greetings from adapter server!") under ```response``` element.

```
  <ts:request>
    <ts:name>Test message</ts:name>
  </ts:request>
  <ts:response>
    <ts:message>Hello Test message! Greetings from adapter server!</ts:message>
  </ts:response>
```

##### Server

Coming soon...


