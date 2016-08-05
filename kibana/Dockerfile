FROM ubuntu:14.04
MAINTAINER Christian Cassar <ccassar@cisco.com>

RUN http_proxy="" apt-get update -q && \
    http_proxy="" DEBIAN_FRONTEND=noninteractive apt-get install -y \
    curl

ENV KIBANA_VERSION 4.5.0
RUN https_proxy="" curl -s https://download.elasticsearch.org/kibana/kibana/kibana-$KIBANA_VERSION-linux-x64.tar.gz | tar xz -C /opt
RUN ln -s /opt/kibana-* /opt/kibana

#
# Define where we mount config and logs on host
#

ENV PATH /opt/kibana/bin:$PATH

WORKDIR /opt/kibana

VOLUME /opt/kibana/data

EXPOSE 5601

CMD ["kibana","--config ./data/kibana.yml","--log-file ./data/kibana.log"]

