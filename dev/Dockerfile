FROM openjdk:17.0.1-slim-buster

RUN jlink --add-modules java.base --output /tmp/jre

FROM bitnami/minideb:buster-snapshot-20220109T212658Z

COPY --from=0 /tmp/jre /jre
ENV PATH=/jre/bin:$PATH
