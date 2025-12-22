FROM babashka/babashka:1.12.213

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
    openjdk-25-jdk-headless \
    git \
 && bb clojure \
 && rm -fr /var/lib/apt/lists/*

