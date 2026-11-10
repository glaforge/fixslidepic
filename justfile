# ==============================================================================
# FixSlidePic — Cloud Run Deployment & Workflow Automation
# ==============================================================================
# Copy .env.example to .env to configure your Google Cloud account, project,
# region, and service settings.
# ==============================================================================

set dotenv-load

# Google Cloud Platform configuration (loaded from untracked .env file)
account       := env_var_or_default("GOOGLE_CLOUD_ACCOUNT", "your-account@example.com")
project       := env_var_or_default("GOOGLE_CLOUD_PROJECT", "your-gcp-project")
region        := env_var_or_default("CLOUD_RUN_REGION", "europe-west1")
service       := env_var_or_default("CLOUD_RUN_SERVICE", "your-service-name")
domain        := env_var_or_default("CLOUD_RUN_DOMAIN", "your-domain.example.com")
base_image    := env_var_or_default("CLOUD_RUN_BASE_IMAGE", "google-24/java25")
memory        := env_var_or_default("CLOUD_RUN_MEMORY", "2Gi")
cpu           := env_var_or_default("CLOUD_RUN_CPU", "2")
secret        := env_var_or_default("CLOUD_RUN_SECRET", "GEMINI_API_KEY=GEMINI_API_KEY:latest")
max_instances := env_var_or_default("CLOUD_RUN_MAX_INSTANCES", "3")
concurrency   := env_var_or_default("CLOUD_RUN_CONCURRENCY", "20")

# Default: List all available recipes
default:
    @just --list

# View resolved variables and active configuration
config:
    @echo "Active Configuration:"
    @echo "  GOOGLE_CLOUD_ACCOUNT: {{account}}"
    @echo "  GOOGLE_CLOUD_PROJECT: {{project}}"
    @echo "  CLOUD_RUN_REGION:     {{region}}"
    @echo "  CLOUD_RUN_SERVICE:    {{service}}"
    @echo "  CLOUD_RUN_DOMAIN:     {{domain}}"
    @echo "  CLOUD_RUN_BASE_IMAGE: {{base_image}}"
    @echo "  CPU / MEMORY:         {{cpu}} vCPU / {{memory}}"

# Run the Micronaut application locally
run:
    ./gradlew run

# Run all unit and integration tests
test:
    ./gradlew test

# Package the shadow fat JAR and stage it for Cloud Run build-less deployment
build:
    ./gradlew shadowJar
    mkdir -p build/deploy
    cp build/libs/fix-slide-pic-0.1-all.jar build/deploy/app.jar

# Clean build artifacts and staging directories
clean:
    ./gradlew clean
    rm -rf build/deploy

# Deploy the staged JAR to Cloud Run without Cloud Build (Java 25 base image)
deploy-app: build
    gcloud beta run deploy {{service}} \
        --account={{account}} \
        --project={{project}} \
        --region={{region}} \
        --source=build/deploy \
        --base-image={{base_image}} \
        --no-build \
        --set-secrets={{secret}} \
        --set-env-vars=MICRONAUT_SERVER_PORT=8080,SLIDE_FIXER_OUTPUT_DIR=/tmp/generated-slides \
        --memory={{memory}} \
        --cpu={{cpu}} \
        --max-instances={{max_instances}} \
        --concurrency={{concurrency}} \
        --session-affinity \
        --allow-unauthenticated \
        --quiet

# Create or verify the Cloud Run vanity URL domain mapping
map-domain:
    gcloud alpha run domain-mappings create \
        --account={{account}} \
        --project={{project}} \
        --region={{region}} \
        --service={{service}} \
        --domain={{domain}} \
        --quiet || true

# Full deployment: build JAR, deploy to Cloud Run, and map vanity URL
deploy: deploy-app map-domain
    @echo "🚀 Deployed https://{{domain}}"

# Show Cloud Run service and domain mapping status
status:
    gcloud run services describe {{service}} \
        --account={{account}} \
        --project={{project}} \
        --region={{region}} \
        --format="table(status.url:label=URL,status.latestReadyRevisionName:label=REVISION,status.conditions[0].status:label=READY)"
    gcloud alpha run domain-mappings describe \
        --account={{account}} \
        --project={{project}} \
        --region={{region}} \
        --domain={{domain}} \
        --format="table(metadata.name:label=DOMAIN,spec.routeName:label=SERVICE,status.conditions[0].status:label=READY)"

# Tail live application logs from Cloud Run
logs:
    gcloud beta run services logs read {{service}} \
        --account={{account}} \
        --project={{project}} \
        --region={{region}} \
        --limit=50
