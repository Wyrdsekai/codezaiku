FROM python:3.12-alpine
RUN pip install --no-cache-dir cryptography
COPY gen_and_serve.py /gen_and_serve.py
CMD ["python", "/gen_and_serve.py"]
