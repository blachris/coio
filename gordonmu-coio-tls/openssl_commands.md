## OpenSSL Commands

Generate ecdsa private key with named curve:
    
    openssl ecparam -name prime256v1 -genkey -out key.pem 

Convert a key from openssl format to PKCS#8 format

    openssl pkcs8 -topk8 -nocrypt -in key.pem -out key_pkcs8.pem 

Create self signed cert with given private key, fields being queried interactively

    openssl req -key key.pem -nodes -x509 -days 365 -out cert.pem -config .\openssl.cnf

Print cert nicely

    openssl x509 -in cert.pem -text -noout

Create a cert sign request

    openssl req -key key.pem -new -out cert.csr -config .\openssl.cnf

Straight up sign given cert sign request with given key

    openssl x509 -req -days 365 -in cert.csr -signkey root-key.pem -out cert.pem

Sign cert sign request with given issuer cert and key (also needs a serial file that needs to be created once)

    openssl x509 -req -CA root-cert.pem -CAkey root-key.pem -CAcreateserial -days 365 -in cert.csr -out cert.pem
    
    
### Workflow

1. Create a root private key
1. Create a self signed root certificate
1. Convert root private key to pkcs8
1. Create a server private key
1. Create a cert sign request for the server, passing the server private key 
1. Sign the server sign request, passing the root privat key, root cert and the server sign request
1. Convert server key to pkcs8

