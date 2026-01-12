terraform {
  required_providers {
    aiven = {
      source  = "aiven/aiven"
      version = "~> 4.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "3.7.2"
    }
    local = {
      source  = "hashicorp/local"
      version = "2.5.3"
    }
  }
}
variable "aiven_api_token" {
  type      = string
  sensitive = true
}
variable "aiven_cloud_region" {
  type = string
}

provider "aiven" {
  api_token = var.aiven_api_token
}

resource "aiven_organization" "chok_org" {
  name = "chat_over_kafka_org"
}

resource "aiven_project" "chok_project" {
  parent_id = aiven_organization.chok_org.id
  project   = "chok-project-0-0-1"
}
resource "aiven_kafka" "kafka_cluster" {
  plan         = "free-0"
  cloud_name = var.aiven_cloud_region
  project      = aiven_project.chok_project.project
  service_name = "chok-free-kafka-service"
}
data "aiven_kafka" "kafka"{
  project = aiven_project.chok_project.project
  service_name = aiven_kafka.kafka_cluster.service_name
}



resource "aiven_kafka_topic" "audio1" {
  service_name = data.aiven_kafka.kafka.service_name
  project      = aiven_project.chok_project.project
  topic_name   = "chok-audio-1"
  partitions   = 2
  replication  = 2
}
resource "aiven_kafka_topic" "audio2" {
  service_name = data.aiven_kafka.kafka.service_name
  project      = aiven_project.chok_project.project
  topic_name   = "chok-audio-2"
  partitions   = 2
  replication  = 2
}

resource "aiven_kafka_topic" "md1" {
  service_name = data.aiven_kafka.kafka.service_name
  project      = aiven_project.chok_project.project
  topic_name   = "chok-metadata-1"
  partitions   = 2
  replication  = 2
  config {
    cleanup_policy = "compact"
  }
}
resource "aiven_kafka_topic" "md2" {
  service_name = data.aiven_kafka.kafka.service_name
  project      = aiven_project.chok_project.project
  topic_name   = "chok-metadata-2"
  partitions   = 2
  replication  = 2
  config {
    cleanup_policy = "compact"
  }
}

resource "random_password" "dev_password" {
  length = 10
  special = false
}

resource "aiven_kafka_user" "dev_user" {
  project      = aiven_project.chok_project.project
  service_name = data.aiven_kafka.kafka.service_name
  username     = "dev_user"
  password     = random_password.dev_password.result
}

resource "aiven_kafka_acl" "dev_acl" {
  project      = aiven_project.chok_project.project
  service_name = data.aiven_kafka.kafka.service_name
  topic        = "*"
  permission   = "readwrite"
  username     = "*"
}


resource "local_sensitive_file" "ca_cert" {
  content = aiven_project.chok_project.ca_cert
  filename = "../app/src/main/assets/ca.pem"
}

resource "local_sensitive_file" "dev_client_cert" {
  filename = "../app/src/main/assets/client.pem"
  content  = aiven_kafka_user.dev_user.access_cert
}

resource "local_sensitive_file" "dev_client_key" {
  filename = "../app/src/main/assets/client.key"
  content  = aiven_kafka_user.dev_user.access_key
}

locals {
  assets_dir = "../app/src/main/assets"

  broker_url = replace(
    data.aiven_kafka.kafka.service_uri,
    "kafka://",
    ""
  )

  channels = [
    {
      channelNumber     = 1
      channelName       = "Main"
      audioTopic        = aiven_kafka_topic.audio1.topic_name
      audioPartition    = 0
      metadataTopic     = aiven_kafka_topic.md1.topic_name
      metadataPartition = 0
    },
    {
      channelNumber     = 2
      channelName       = "Secondary"
      audioTopic        = aiven_kafka_topic.audio2.topic_name
      audioPartition    = 0
      metadataTopic     = aiven_kafka_topic.md2.topic_name
      metadataPartition = 0
    }
  ]
  kafka_config = {
    brokerUrl = local.broker_url
    channels = local.channels
    certificates = {
      caAssetName         = "ca.pem"
      clientKeyAssetName  = "client.key"
      clientCertAssetName = "client.pem"
    }
  }
}

resource "local_sensitive_file" "kafka_config" {
  filename = "${local.assets_dir}/kafka_config.json"
  content  = jsonencode(local.kafka_config)
}

