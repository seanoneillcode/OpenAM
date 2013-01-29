//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v1.0.6-b27-fcs 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2012.06.11 at 10:34:07 AM PDT 
//


package com.sun.identity.saml2.jaxb.xmlsig;


/**
 * Java content class for X509DataType complex type.
 * <p>The following schema fragment specifies the expected content contained within this java content object. (defined at file:/Users/allan/A-SVN/trunk/opensso/products/federation/library/xsd/saml2/xmldsig-core-schema.xsd line 186)
 * <p>
 * <pre>
 * &lt;complexType name="X509DataType">
 *   &lt;complexContent>
 *     &lt;restriction base="{http://www.w3.org/2001/XMLSchema}anyType">
 *       &lt;sequence maxOccurs="unbounded">
 *         &lt;choice>
 *           &lt;element name="X509IssuerSerial" type="{http://www.w3.org/2000/09/xmldsig#}X509IssuerSerialType"/>
 *           &lt;element name="X509SKI" type="{http://www.w3.org/2001/XMLSchema}base64Binary"/>
 *           &lt;element name="X509SubjectName" type="{http://www.w3.org/2001/XMLSchema}string"/>
 *           &lt;element name="X509Certificate" type="{http://www.w3.org/2001/XMLSchema}base64Binary"/>
 *           &lt;element name="X509CRL" type="{http://www.w3.org/2001/XMLSchema}base64Binary"/>
 *           &lt;any/>
 *         &lt;/choice>
 *       &lt;/sequence>
 *     &lt;/restriction>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 */
public interface X509DataType {


    /**
     * Gets the value of the X509IssuerSerialOrX509SKIOrX509SubjectName property.
     * 
     * <p>
     * This accessor method returns a reference to the live list,
     * not a snapshot. Therefore any modification you make to the
     * returned list will be present inside the JAXB object.
     * This is why there is not a <CODE>set</CODE> method for the X509IssuerSerialOrX509SKIOrX509SubjectName property.
     * 
     * <p>
     * For example, to add a new item, do as follows:
     * <pre>
     *    getX509IssuerSerialOrX509SKIOrX509SubjectName().add(newItem);
     * </pre>
     * 
     * 
     * <p>
     * Objects of the following type(s) are allowed in the list
     * {@link com.sun.identity.saml2.jaxb.xmlsig.X509DataType.X509SubjectName}
     * {@link com.sun.identity.saml2.jaxb.xmlsig.X509DataType.X509IssuerSerial}
     * {@link com.sun.identity.saml2.jaxb.xmlsig.X509DataType.X509Certificate}
     * {@link java.lang.Object}
     * {@link com.sun.identity.saml2.jaxb.xmlsig.X509DataType.X509CRL}
     * {@link com.sun.identity.saml2.jaxb.xmlsig.X509DataType.X509SKI}
     * 
     */
    java.util.List getX509IssuerSerialOrX509SKIOrX509SubjectName();


    /**
     * Java content class for X509CRL element declaration.
     * <p>The following schema fragment specifies the expected content contained within this java content object. (defined at file:/Users/allan/A-SVN/trunk/opensso/products/federation/library/xsd/saml2/xmldsig-core-schema.xsd line 193)
     * <p>
     * <pre>
     * &lt;element name="X509CRL" type="{http://www.w3.org/2001/XMLSchema}base64Binary"/>
     * </pre>
     * 
     */
    public interface X509CRL
        extends javax.xml.bind.Element
    {


        /**
         * Gets the value of the value property.
         * 
         * @return
         *     possible object is
         *     byte[]
         */
        byte[] getValue();

        /**
         * Sets the value of the value property.
         * 
         * @param value
         *     allowed object is
         *     byte[]
         */
        void setValue(byte[] value);

    }


    /**
     * Java content class for X509Certificate element declaration.
     * <p>The following schema fragment specifies the expected content contained within this java content object. (defined at file:/Users/allan/A-SVN/trunk/opensso/products/federation/library/xsd/saml2/xmldsig-core-schema.xsd line 192)
     * <p>
     * <pre>
     * &lt;element name="X509Certificate" type="{http://www.w3.org/2001/XMLSchema}base64Binary"/>
     * </pre>
     * 
     */
    public interface X509Certificate
        extends javax.xml.bind.Element
    {


        /**
         * Gets the value of the value property.
         * 
         * @return
         *     possible object is
         *     byte[]
         */
        byte[] getValue();

        /**
         * Sets the value of the value property.
         * 
         * @param value
         *     allowed object is
         *     byte[]
         */
        void setValue(byte[] value);

    }


    /**
     * Java content class for X509IssuerSerial element declaration.
     * <p>The following schema fragment specifies the expected content contained within this java content object. (defined at file:/Users/allan/A-SVN/trunk/opensso/products/federation/library/xsd/saml2/xmldsig-core-schema.xsd line 189)
     * <p>
     * <pre>
     * &lt;element name="X509IssuerSerial" type="{http://www.w3.org/2000/09/xmldsig#}X509IssuerSerialType"/>
     * </pre>
     * 
     */
    public interface X509IssuerSerial
        extends javax.xml.bind.Element, com.sun.identity.saml2.jaxb.xmlsig.X509IssuerSerialType
    {


    }


    /**
     * Java content class for X509SKI element declaration.
     * <p>The following schema fragment specifies the expected content contained within this java content object. (defined at file:/Users/allan/A-SVN/trunk/opensso/products/federation/library/xsd/saml2/xmldsig-core-schema.xsd line 190)
     * <p>
     * <pre>
     * &lt;element name="X509SKI" type="{http://www.w3.org/2001/XMLSchema}base64Binary"/>
     * </pre>
     * 
     */
    public interface X509SKI
        extends javax.xml.bind.Element
    {


        /**
         * Gets the value of the value property.
         * 
         * @return
         *     possible object is
         *     byte[]
         */
        byte[] getValue();

        /**
         * Sets the value of the value property.
         * 
         * @param value
         *     allowed object is
         *     byte[]
         */
        void setValue(byte[] value);

    }


    /**
     * Java content class for X509SubjectName element declaration.
     * <p>The following schema fragment specifies the expected content contained within this java content object. (defined at file:/Users/allan/A-SVN/trunk/opensso/products/federation/library/xsd/saml2/xmldsig-core-schema.xsd line 191)
     * <p>
     * <pre>
     * &lt;element name="X509SubjectName" type="{http://www.w3.org/2001/XMLSchema}string"/>
     * </pre>
     * 
     */
    public interface X509SubjectName
        extends javax.xml.bind.Element
    {


        /**
         * Gets the value of the value property.
         * 
         * @return
         *     possible object is
         *     {@link java.lang.String}
         */
        java.lang.String getValue();

        /**
         * Sets the value of the value property.
         * 
         * @param value
         *     allowed object is
         *     {@link java.lang.String}
         */
        void setValue(java.lang.String value);

    }

}
