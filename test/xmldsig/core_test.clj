(ns xmldsig.core-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [xmldsig.core :refer :all])
  (:import [java.io FileNotFoundException]
           [java.security.cert X509Certificate]
           [java.security PublicKey PrivateKey]
           [javax.xml.crypto.dsig.keyinfo KeyInfo]))


(deftest load-certificate-test
  (is (thrown? FileNotFoundException (load-certificate "")))
  (is (instance? X509Certificate (load-certificate "test-resources/test-user-cert.pem"))))


(deftest get-public-key-test
  (is (instance? PublicKey (get-public-key
                            (load-certificate "test-resources/test-user-cert.pem")))))


(deftest load-private-key-test
  (is (instance? PrivateKey (load-private-key "test-resources/test-user-key.pkcs"))))


(deftest validate-signature-test
  (let [pk   (load-private-key "test-resources/test-user-key.pkcs")
        cert (load-certificate "test-resources/test-user-cert.pem")
        xml  (slurp "test-resources/data.xml")
        signed (sign pk cert xml)
        signed-wo-cert (sign pk nil xml)]
    (is (= signed
           (slurp "test-resources/data-signed.xml")))
    (is (= signed-wo-cert
           (slurp "test-resources/data-signed-wo-cert.xml")))
    (is (instance? KeyInfo (validate signed (get-public-key cert))))
    (is (nil? (validate (str/replace signed "milk" "beer") (get-public-key cert))))
    (is (validate signed-wo-cert (get-public-key cert)))
    (is (nil? (validate (str/replace signed-wo-cert "milk" "beer") (get-public-key cert))))))
