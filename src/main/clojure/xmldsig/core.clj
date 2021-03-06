(ns xmldsig.core
  (:import [javax.xml.crypto.dsig.dom DOMSignContext DOMValidateContext]
           [javax.xml.crypto.dsig XMLSignatureFactory Transform DigestMethod Reference SignedInfo CanonicalizationMethod SignatureMethod XMLSignature]
           [java.util Collections ArrayList HashSet]
           [javax.xml.crypto.dsig.spec TransformParameterSpec C14NMethodParameterSpec]
           [java.io ByteArrayOutputStream]
           [org.w3c.dom Document NodeList]
           [javax.xml.crypto.dsig.keyinfo KeyInfoFactory KeyInfo X509Data]
           [java.security KeyFactory PrivateKey PublicKey Key]
           [java.security.spec PKCS8EncodedKeySpec]
           [java.security.cert CertificateFactory Certificate X509Certificate X509CertSelector TrustAnchor PKIXBuilderParameters CertPathBuilder]
           [javax.xml.crypto KeySelector]
           [xmldsig X509KeySelector])
  (:require [clojure.java.io :as io]
           [clojure.tools.logging :refer [debug info warn error]]
           [xmldsig.xml :as xml]))


(declare load-certificate)

(defn- create-x509-data ;; todo possible options what to include in X.509 data
  [^KeyInfoFactory kif ^X509Certificate cert]
  (let [x509-content (doto (ArrayList.)
                       (.add (.. cert getSubjectX500Principal getName))
                       (.add cert))]

    (.newX509Data kif x509-content)))


(defn sign
  ;; todo: provide optional parameter with configuration map (signature method, canonicalization method, ...)
  "Signs XML document using the given private key and attaching (if given) X.509 certificate."
  [^PrivateKey private-key ^X509Certificate cert ^String xml-string]
  (let [doc                                  (xml/parse xml-string)

        ^DOMSignContext dsc                  (DOMSignContext. private-key (.getDocumentElement doc))
        ^XMLSignatureFactory fac             (XMLSignatureFactory/getInstance "DOM")

        ^TransformParameterSpec tps          nil
        ref                                  (cast Reference (.newReference fac
                                                               ""
                                                               (.newDigestMethod fac DigestMethod/SHA1 nil)
                                                               (Collections/singletonList
                                                                 (.newTransform fac
                                                                   Transform/ENVELOPED
                                                                   tps))
                                                               nil nil))

        ^C14NMethodParameterSpec c14n-method nil
        si                                   (cast SignedInfo
                                               (.newSignedInfo fac
                                                 (.newCanonicalizationMethod fac
                                                   CanonicalizationMethod/INCLUSIVE_WITH_COMMENTS
                                                   c14n-method)
                                                 (.newSignatureMethod fac SignatureMethod/RSA_SHA1 nil)
                                                 (Collections/singletonList ref)))

        ^KeyInfo ki                          (when cert
                                               (let [^KeyInfoFactory kif (.getKeyInfoFactory fac)]
                                                 (.newKeyInfo
                                                  kif
                                                  (doto (ArrayList.)
                                                    (.add ^X509Data (create-x509-data kif cert))))))
         ^XMLSignature signature              (.newXMLSignature fac si ki)]

    (.sign signature dsc)
    (xml/serialise doc)))


(def ^HashSet get-trust-anchors
  (memoize
    #(let [^X509Certificate ca-cert (load-certificate nil)] ;; todo: create mechanism to pass trust anchors
      ;; This can be key store if more than one truted key needed
      (doto (HashSet.)
        (.add (TrustAnchor. ca-cert nil))))))


(defn- validate-certificate-path
  "Validates certification path. Returns true if certification path was bult successfully.
  If verification of any certificate in the chaing fails, function returns false."
  [^X509Certificate cert]
  (let [^X509CertSelector selector    (doto (X509CertSelector.) (.setCertificate cert))

        ;; Also possible add all intermediates if certificatioin chaing is longer than 1
        ;; CertStoreParameters intermediates = new CollectionCertStoreParameters(listOfIntermediateCertificates)
        ;; params.addCertStore(CertStore.getInstance("Collection", intermediates));

        ^PKIXBuilderParameters params (doto
                                        (PKIXBuilderParameters. (get-trust-anchors) selector)
                                        (.setRevocationEnabled false))]
    (try
      (-> "PKIX"
          (CertPathBuilder/getInstance)
          (.build params)
          nil?
          not)
      (catch Throwable th (error th "Couldn't validate certificate path for certificate:" cert)))))


(defn validate-signature
  "Validates signature attached to the XML string with key matching key-selector. If signature is valid, returns KeyInfo (or true if no KeyInfo attached)
  used for signature validation, otherwise nil

  *DOES NOT VALIDATE CERTIFICATE CHAIN*"
  [xml-string ^KeySelector key-selector]
  (let [^Document doc                (xml/parse xml-string)
        ^NodeList sig-elem-node-list (.getElementsByTagNameNS doc XMLSignature/XMLNS "Signature")]

    (when-let [signature-node (.item sig-elem-node-list 0)]
      (let [^XMLSignatureFactory fac    (XMLSignatureFactory/getInstance "DOM")
            ^DOMValidateContext val-ctx (DOMValidateContext. key-selector signature-node)
            ^XMLSignature signature     (.unmarshalXMLSignature fac val-ctx)

            valid                       (.validate signature val-ctx)]
        (if-not valid
          (do (warn "Signature failed core validation")
              (let [sv (-> signature .getSignatureValue (.validate val-ctx))]
                (warn "Signature validation status:" sv))
              (doseq [^Reference ref (-> signature .getSignedInfo .getReferences)]
                (let [ref-valid (.validate ref val-ctx)]
                  (warn "Reference URI" (str "'" (.getURI ref) "'") "validity status:" ref-valid))))
          (or (.getKeyInfo signature) true))))))


(defn validate
  "This is rather testing method than proper cert and PKI verification. Validates signature with the given validating-key.
  If no validating key is provided, it tries to read certificate from X509Data element nested in the document
  It doesn't do any PKI/Cert path/cert revokation validation and check"
  ([xml-string]
   (let [key-selector      (X509KeySelector.)
         ^KeyInfo key-info (validate-signature xml-string key-selector)]
     (if key-info
       ;; document wasn't tampered and signature is valid.
       ;; Now, let's build certificate chain for signing certificate and validate it
       (validate-certificate-path (.findCertificate key-selector key-info))
       false)))

  ([xml-string ^Key validating-key]
   (validate-signature xml-string (KeySelector/singletonKeySelector validating-key))))


(defn input->byte-array
  "Tries to coerce input to byte array."
  [input]
  (with-open [out (ByteArrayOutputStream.)]
    (-> input
        io/input-stream
        (io/copy out))
    (.toByteArray out)))


(defn ^PrivateKey load-private-key
  "Loads PKCS#8 encoded private key from the input. Assumes that the key algorithm is RSA. Accepts any input valid for clojure.java.io/input-stream"
  [private-key]
  (->> private-key
       input->byte-array
       PKCS8EncodedKeySpec.
       (.generatePrivate
         (KeyFactory/getInstance "RSA"))))


(defn ^PublicKey load-public-key
  "Loads PKCS#8 encoded public key from a file. Assumes that the key algorithm is RSA. Accepts any input valid for clojure.java.io/input-stream"
  [pub-key]
  (->> pub-key
       input->byte-array
       PKCS8EncodedKeySpec.
       (.generatePublic
         (KeyFactory/getInstance "RSA"))))


(defn ^X509Certificate load-certificate
  "Loads X.509 certificate from the data passed as an argument. Argument can by anything suppported by clojure.java.io/input-stream"
  [certificate]
  (->> certificate
       io/input-stream
       (.generateCertificate
         (CertificateFactory/getInstance "X.509"))))


(defn ^PublicKey get-public-key
  "Reads public key from a X.509 certificate file"
  [^Certificate obj]
  (.getPublicKey obj))
