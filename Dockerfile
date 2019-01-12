FROM ip4368/watchman:latest

RUN apt-get update \
  && apt-get install -y \
    openjdk-8-jdk \
    openjdk-8-jre-headless \
    ant \
    python \
    git

ADD . /buck/

WORKDIR /buck

RUN ant \
  && ./bin/buck build --show-output buck \
  && cp buck-out/gen/programs/buck.pex /usr/local/bin/buck

WORKDIR /
RUN rm -rf /buck
