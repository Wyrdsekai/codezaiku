FROM python:3.12-alpine
RUN pip install --no-cache-dir redis==5.0.8
COPY server.py /server.py
EXPOSE 8000
CMD ["python", "/server.py"]
