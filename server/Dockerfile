FROM python:3.13-slim
WORKDIR /opt/bridge
COPY requirements.txt .
RUN pip install --disable-pip-version-check --no-cache-dir -r requirements.txt
COPY mobile_push ./mobile_push
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN install -d -m 0700 -o 10000 -g 10000 /var/lib/hermes-mobile-push
ENV MOBILE_PUSH_STATE=/var/lib/hermes-mobile-push
USER 10000:10000
ENTRYPOINT ["python", "-m", "mobile_push"]
