// Builds the SurShongi API image for Kubernetes.
//   qa   -> qa/surshongi-api:<TAG>
//   pps  -> pps/surshongi-api:<TAG>
//   prod -> prod/surshongi-api:<TAG>

variable "REGISTRY" {
  default = ""
}

variable "APP_NAME" {
  default = "surshongi-api"
}

variable "TAG" {
  default = "0.1"
}

variable "GIT_SHA" {
  default = "local"
}

function "image_repo" {
  params = [env_ns]
  result = REGISTRY != "" ? "${REGISTRY}/${env_ns}/${APP_NAME}" : "${env_ns}/${APP_NAME}"
}

group "default" {
  targets = ["prod"]
}

group "all" {
  targets = ["qa", "pps", "prod"]
}

target "_common" {
  context    = "."
  dockerfile = "Dockerfile"
  platforms  = ["linux/amd64"]
  labels = {
    "org.opencontainers.image.title"    = "SurShongi API"
    "org.opencontainers.image.version"  = "${TAG}"
    "org.opencontainers.image.revision" = "${GIT_SHA}"
  }
}

target "qa" {
  inherits = ["_common"]
  tags = [
    "${image_repo("qa")}:${TAG}",
    "${image_repo("qa")}:latest",
  ]
}

target "pps" {
  inherits = ["_common"]
  tags = [
    "${image_repo("pps")}:${TAG}",
    "${image_repo("pps")}:latest",
  ]
}

target "prod" {
  inherits = ["_common"]
  tags = [
    "${image_repo("prod")}:${TAG}",
    "${image_repo("prod")}:latest",
  ]
}
