"""A TLS endpoint whose certificate validity is set by env — so the fixture can be expired ON PURPOSE.

openssl cannot set an arbitrary notBefore/notAfter from the command line in the versions shipped in
common base images (`req` and `x509 -req` both reject -not_before/-not_after), which is why this is
Python: cryptography sets both explicitly and deterministically.
"""
import datetime as dt
import http.server
import os
import ssl

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

CERT, KEY = "/certs/server.crt", "/certs/server.key"
DAYS_AGO = int(os.environ.get("CERT_EXPIRED_DAYS_AGO", "0"))   # 0 = valid cert


def write_cert():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    now = dt.datetime.now(dt.timezone.utc)
    if DAYS_AGO > 0:                       # THE FAULT: a cert that expired DAYS_AGO days back
        not_before = now - dt.timedelta(days=DAYS_AGO + 365)
        not_after = now - dt.timedelta(days=DAYS_AGO)
    else:
        not_before, not_after = now - dt.timedelta(days=1), now + dt.timedelta(days=365)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "tls")])
    cert = (x509.CertificateBuilder()
            .subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(not_before).not_valid_after(not_after)
            .add_extension(x509.SubjectAlternativeName([x509.DNSName("tls")]), critical=False)
            .sign(key, hashes.SHA256()))
    os.makedirs("/certs", exist_ok=True)
    with open(KEY, "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.PEM,
                                  serialization.PrivateFormat.TraditionalOpenSSL,
                                  serialization.NoEncryption()))
    with open(CERT, "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))
    print(f"cert notBefore={not_before} notAfter={not_after}", flush=True)


class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.end_headers(); self.wfile.write(b"ok")
    def log_message(self, *a): pass


if __name__ == "__main__":
    write_cert()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(CERT, KEY)
    srv = http.server.HTTPServer(("0.0.0.0", 8443), H)
    srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    print("tls endpoint listening on 8443", flush=True)
    srv.serve_forever()
