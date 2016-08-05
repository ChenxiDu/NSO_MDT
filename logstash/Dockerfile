FROM java:8
MAINTAINER Christian Cassar <ccassar@cisco.com>

RUN http_proxy="" apt-get update -q && \
    http_proxy="" DEBIAN_FRONTEND=noninteractive apt-get install -y \
    curl \
    ruby \
    protobuf-compiler \
    ruby-dev

ENV LOGSTASH_VERSION 2.3.1

#
# Pull and install logstash
#
RUN https_proxy="" curl -s https://download.elasticsearch.org/logstash/logstash/logstash-$LOGSTASH_VERSION.tar.gz | tar xz -C /opt
RUN ln -s /opt/logstash-$LOGSTASH_VERSION /opt/logstash

#
# Define where we mount logs on host
#

ENV PATH /opt/logstash/bin:$PATH

WORKDIR /opt/logstash
	
VOLUME /opt/logstash/data

#
# Setup our plugins
#
ADD builder ./builder
RUN http_proxy="" /opt/logstash/bin/plugin install --no-verify logstash-mixin-http_client
RUN http_proxy="" find ./builder -name *.gem -exec /opt/logstash/bin/plugin install --no-verify {} +

#
# Start up script which reworks config based on env variables if
# necessary.
#

ADD start.sh ./start.sh
RUN chmod +x ./start.sh
CMD ["./start.sh"]
