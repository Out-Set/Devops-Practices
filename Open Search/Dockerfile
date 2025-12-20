# Logstash with opensearch-plugin
FROM docker.elastic.co/logstash/logstash:8.15.1
RUN bin/logstash-plugin install --no-verify logstash-output-opensearch
RUN bin/logstash-plugin install --no-verify logstash-input-opensearch