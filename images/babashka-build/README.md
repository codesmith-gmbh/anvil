# Once per custom registry, depending on the platform

```bash 
docker build --platform=linux/amd64 -t c8n.io/stan/clj-build:jdk25-bb1.12.213-1 .
```

```bash
docker push c8n.io/stan/clj-build:jdk25-bb1.12.213-1
```