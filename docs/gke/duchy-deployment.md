# How to deploy a Halo Duchy on GKE

## Background

The configuration for the [`dev` environment](../../src/main/k8s/dev) can be
used as the basis for deploying CMMS components using Google Kubernetes Engine
(GKE) on another Google Cloud project.

***Disclaimer***:

-   This guide is just one way of achieving the goal, not necessarily the best
    approach.
-   Almost all steps can be done via either the
    [Google Cloud Console](https://console.cloud.google.com/) UI or the
    [`gcloud` CLI](https://cloud.google.com/sdk/gcloud/reference). The doc picks
    the easier one for each step. But you are free to do it in an alternative
    way.
-   All names used in this doc can be replaced with something else. We use
    specific names in the doc for ease of reference.
-   All quotas and resource configs are just examples, adjust the quota and size
    based on the actual usage.
-   In the doc, we assume we are deploying to a single region, i.e. us-central1.
    If you are deploying to another region or multiple regions, just need to
    adjust each step mentioning "region" accordingly.

## What are we creating/deploying?

For a Duchy named `worker1`:

-   1 Cloud Spanner database
-   1 Cloud Storage bucket
-   1 GKE cluster
-   1 Kubernetes secret
-   1 Kubernetes configmap
-   4 Kubernetes services
    -   worker1-async-computation-control-server (Cluster IP)
    -   worker1-computation-control-server (External load balancer)
    -   worker1-requisition-fulfillment-server (External load balancer)
    -   worker1-spanner-computations-server (Cluster IP)
-   6 Kubernetes deployments
    -   worker1-async-computation-control-server-deployment (gRPC service)
    -   worker1-computation-control-server-deployment (gRPC service)
    -   worker1-herald-daemon-deployment (Daemon Job)
    -   worker1-liquid-legions-v2-mill-daemon-deployment (Daemon Job)
    -   worker1-requisition-fulfillment-server-deployment (gRPC service)
    -   worker1-spanner-computations-server-deployment (gRPC service)
-   8 Kubernetes network policies
    -   worker1-async-computation-controls-server-network-policy
    -   worker1-computation-control-server-network-policy
    -   worker1-herald-daemon-network-policy
    -   worker1-liquid-legions-v2-mill-daemon-network-policy
    -   worker1-push-spanner-schema-job-network-policy
    -   worker1-requisition-fulfillment-server-network-policy
    -   worker1-spanner-computations-server-network-policy
    -   default-deny-ingress-and-egress

## Step 0. Before You Start

Follow Step 0 of the
[Kingdom deployment guide](kingdom-deployment.md#step-0-before-you-start).

## Step 1. Register your duchy with the kingdom (offline)

In order to join the Cross-Media Measurement System, the Duchy needs to first be
registered with the Kingdom. This will be done offline with the help from the
Kingdom operator.

The Duchy operator needs to share the following information with the Kingdom
operator:

-   The name (a string, used as an ID) of the Duchy (unique amongst all Duchies)
-   The CA ("root") certificate
-   A consent signaling ("leaf") certificate

The Kingdom operator will
[register all corresponding resources](../operations/creating-resources.md) for
the Duchy via internal admin tools. The resource names will be shared with the
Duchy operator.

## Step 2. Create the database

The Duchy expects its own database within your Spanner instance. You can create
one with the `gcloud` CLI. For example, a database named
`worker1_duchy_computations` in the `dev-instance` instance.

```shell
gcloud spanner databases create worker1_duchy_computations \
  --instance=dev-instance
```

## Step 3. Create the Cloud Storage Bucket

Each Duchy needs a storage bucket. One can be created from the
[Console](https://console.cloud.google.com/storage/browser). Note that bucket
names are public, globally unique, and cannot be changed once created. See
[Bucket naming guidelines](https://cloud.google.com/storage/docs/naming-buckets).

As the data in this bucket need not be exposed to the public internet, select
"Enforce public access prevention on this bucket".

## Step 4. Build and push the container images

The `dev` configuration uses the
[Container Registry](https://cloud.google.com/container-registry) to store our
docker images. Enable the Google Container Registry API in the console if you
haven't done it. If you use other repositories, adjust the commands accordingly.

Assuming a project named `halo-worker1-demo`, run the following to build the
images:

```shell
bazel query 'filter("push_duchy", kind("container_push", //src/main/docker:all))' |
  xargs bazel build -c opt --define container_registry=gcr.io \
  --define image_repo_prefix=halo-worker1-demo
```

and then push them:

```shell
bazel query 'filter("push_duchy", kind("container_push", //src/main/docker:all))' |
  xargs -n 1 bazel run -c opt --define container_registry=gcr.io \
  --define image_repo_prefix=halo-worker1-demo
```

You should see output like "Successfully pushed Docker image to
gcr.io/halo-worker1-demo/duchy/spanner-update-schema:latest"

Tip: If you're using [Hybrid Development](../building.md#hybrid-development) for
containerized builds, replace `bazel build` with `tools/bazel-container build`
and `bazel run` with `tools/bazel-container-run`. You'll also want to pass the
`-o` option to `xargs`.

## Step 5. Create the Cluster

Follow the steps to
[create resources for the cluster](kingdom-deployment.md#step-3-create-resources-for-the-cluster)
from the Kingdom deployment guide.

Create an additional service account for storage, granting it the Storage Object
Admin role on the Storage bucket. See
[Granting Cloud Storage bucket access](cluster-config.md#granting-cloud-storage-bucket-access).

Enable the Kubernetes API in the console if your account hasn't done it. To
create a cluster named `worker1-duchy` in the `halo-worker1-demo` project, run
the following command:

```shell
gcloud container clusters create worker1-duchy \
  --enable-network-policy --workload-pool=halo-worker1-demo.svc.id.goog \
  --service-account="gke-cluster@halo-worker1-demo.iam.gserviceaccount.com" \
  --database-encryption-key=projects/halo-worker1-demo/locations/us-central1/keyRings/test-key-ring/cryptoKeys/k8s-secret \
  --num-nodes=3 --enable-autoscaling --min-nodes=2 --max-nodes=5 \
  --machine-type=e2-medium
```

Note: The Duchy contains 4 API services and 2 daemon jobs. Those API services
and the Herald daemon don't require too many resources. However, the Mill daemon
performs CPU intensive computations, and may need plenty of replicas depending
on how many active computations the system is expected to process per day.
Select the appropriate machine type and number of nodes based on the traffic and
your budget. For demo purposes, we choose `e2-medium`. In production, you may
want to choose from the
[compute-optimized](https://cloud.google.com/compute/docs/compute-optimized-machines)
machine family (e.g. `c2-standard-4`) which are more expensive.

To configure `kubectl` to access this cluster, run

```shell
gcloud container clusters get-credentials worker1-duchy
```

Now you can follow the steps for
[creating K8s service accounts](kingdom-deployment.md) from the Kingdom
deployment guide. Note that you'll need to follow the steps twice for the two
service accounts. The `dev` configuration assumes that they are named
`internal-server` and `storage`.

## Step 6. Create Kubernetes secrets

***(Note: this step does not use any halo code, and you don't need to do it
within the cross-media-measurement repo.)***

The Duchy binaries are configured to read certificates and config files from a
mounted Kubernetes secret volume.

Prepare all the files we want to include in the Kubernetes secret. The following
files are required in a Duchy:

1.  `all_root_certs.pem`

    This makes up the TLS trusted CA store for the Duchy. It's the concatenation
    of the CA ("root") certificates for all the entites that connect to the
    Duchy, including:

    -   All other Duchies
    -   EDPs that select to fulfill requisitions at this Duchy
    -   This Duchy's own CA certificate (for Duchy internal traffic)
    -   A certificate used for health check purposes

    Supposing your root certs are all in a single folder and end with
    `_root.pem`, you can concatenate them all with a simple shell command:

    ```shell
    cat *_root.pem > all_root_certs.pem
    ```

2.  `worker1_tls.pem`

    The `worker1` Duchy's TLS certificate in PEM format.

3.  `worker1_tls.key`

    The private key for the TLS certificate in PEM format.

4.  `health_probe_tls.pem`

    The client TLS certificate used by the health probe in PEM format.

5.  `health_probe_tls.key`

    The private key for the health probe TLS certificate in PEM format.

6.  `worker1_cs_cert.der`

    The `worker1` Duchy's consent signaling certificate in DER format.

7.  `worker1_cs_private.der`

    The private key for the Duchy's consent signaling certificate in DER format.

8.  `xxx_protocols_setup_config.textproto` (replace xxx with the role)

    -   This contains information about the protocols run in the duchy
    -   Set the role (aggregator or non_aggregator) in the config appropriately
    -   [Example](../../src/main/k8s/testing/secretfiles/aggregator_protocols_setup_config.textproto)

Put all above files in the same folder (anywhere in your local machine), and
create a file named `kustomization.yaml` with the following content:

```yaml
secretGenerator:
- name: certs-and-configs
  files:
  - all_root_certs.pem
  - worker1_tls.pem
  - worker1_tls.key
  - health_probe_tls.pem
  - health_probe_tls.key
  - worker1_cs_cert.der
  - worker1_cs_private.der
  - protocols_setup_config.textproto
```

and run

```shell
kubectl apply -k <path-to-the-above-folder>
```

Now the secret is created in the `halo-cmm-worker1-demo-cluster`. You should be
able to see the secret by running

```shell
kubectl get secrets
```

We assume the name is `certs-and-configs-abcdedf` and will use it in the
following documents.

### Secret files for testing

There are some [secret files](../../src/main/k8s/testing/secretfiles) within the
repository. These can be used to generate a secret for testing, but **must not**
be used for production environments as doing so would be highly insecure.

```shell
bazel run //src/main/k8s/testing/secretfiles:apply_kustomization
```

## Step 7. Create the configmap

Create a `authority_key_identifier_to_principal_map.textproto` file with the
following content. This file contains all EDPs that are allowed to call this
duchy to fulfill the requisitions. If there is no EDP registered yet. Just leave
the file empty.

```prototext
# proto-file: src/main/proto/wfa/measurement/config/authority_key_to_principal_map.proto
# proto-message: AuthorityKeyToPrincipalMap
entries {
  authority_key_identifier: "\xD6\x65\x86\x86\xD8\x7E\xD2\xC4\xDA\xD8\xDF\x76\x39\x66\x21\x3A\xC2\x92\xCC\xE2"
  principal_resource_name: "dataProviders/HRL1wWehTSM"
}
entries {
  authority_key_identifier: "\x6F\x57\x36\x3D\x7C\x5A\x49\x7C\xD1\x68\x57\xCD\xA0\x44\xDF\x68\xBA\xD1\xBA\x86"
  principal_resource_name: "dataProviders/djQdz2ehSSE"
}
entries {
  authority_key_identifier: "\xEE\xB8\x30\x10\x0A\xDB\x8F\xEC\x33\x3B\x0A\x5B\x85\xDF\x4B\x2C\x06\x8F\x8E\x28"
  principal_resource_name: "dataProviders/SQ99TmehSA8"
}
entries {
  authority_key_identifier: "\x74\x72\x6D\xF6\xC0\x44\x42\x61\x7D\x9F\xF7\x3F\xF7\xB2\xAC\x0F\x9D\xB0\xCA\xCC"
  principal_resource_name: "dataProviders/TBZkB5heuL0"
}
entries {
  authority_key_identifier: "\xA6\xED\xBA\xEA\x3F\x9A\xE0\x72\x95\xBF\x1E\xD2\xCB\xC8\x6B\x1E\x0B\x39\x47\xE9"
  principal_resource_name: "dataProviders/HOCBxZheuS8"
}
entries {
  authority_key_identifier: "\xA7\x36\x39\x6B\xDC\xB4\x79\xC3\xFF\x08\xB6\x02\x60\x36\x59\x84\x3B\xDE\xDB\x93"
  principal_resource_name: "dataProviders/VGExFmehRhY"
}
```

Run

```shell
kubectl create configmap config-files \
--from-file=path_to_file/authority_key_identifier_to_principal_map.textproto
```

Whenever there is a new EDP onboarded to the system, you need to add an entry
for this EDP. Update this file and run the following command to replace the
ConfigMap in the cluster

```shell
kubectl create configmap config-files --output=yaml --dry-run=client \
  --from-file=path_to_file/authority_key_identifier_to_principal_map.textproto |
  kubectl replace -f -
```

You can verify that the config file is successfully update by running

```shell
kubectl describe configmaps config-files
```

## Step 8. Create the K8s manifest

Deploying the Duchy to the cluster is generally done by applying a K8s manifest.
You can use the `dev` configuration as a base to get started. The `dev` manifest
is a YAML file that is generated from files written in
[CUE](https://cuelang.org/) using Bazel rules.

The main file for the `dev` Duchy is
[`duchy_gke.cue`](../../src/main/k8s/dev/duchy_gke.cue). Some configuration is
in [`config.cue`](../../src/main/k8s/dev/config.cue) You can modify these file
to specify your own values for your Google Cloud project and Spanner instance.
**Do not** push your modifications to the repository.

For example,

```
# KingdomSystemApiTarget: "your kingdom's system API domain or subdomain:8443"
# GloudProject: "halo-worker1-demo"
# SpannerInstance: "halo-worker1-instance"
# CloudStorageBucket: "halo-worker1-bucket"
```

```
_computation_control_targets: {
  "aggregator": "your aggregator's system API domain:8443"
  "worker1": "your worker1's system API domain:8443"
  "worker2": "your worker2's system API domain:8443"
}
```

You can also modify things such as the memory and CPU request/limit of each pod,
as well as the number of replicas per deployment.

To generate the YAML manifest from the CUE files, run the following
(substituting your own values for the `--define` options):

```shell
bazel build //src/main/k8s/dev:worker1_duchy_gke \
  --define k8s_duchy_secret_name=certs-and-configs-abcdedg \
  --define duchy_cert_id=SVVse4xWHL0 \
  --define duchy_storage_bucket=worker1-duchy
```

You can also do your customization to the generated YAML file rather than to the
CUE file.

Note: The `dev` configuration does not specify a tag or digest for the container
images. You likely want to change this for a production environment.

## Step 9. Apply the K8s manifest

If you're using a manifest generated by the
`//src/main/k8s/dev:worker1_duchy_gke` Bazel target, the command to apply that
manifest is

```shell
kubectl apply -f bazel-bin/src/main/k8s/dev/worker1_duchy_gke.yaml
```

Substitute that path if you're using a different K8s manifest.

Now all Duchy components should be successfully deployed to your GKE cluster.
You can verify by running

```shell
kubectl get deployments
```

and

```shell
kubectl get services
```

You should see something like the following:

```
NAME                                                READY UP-TO-DATE AVAILABLE AGE
worker1-async-computation-control-server-deployment 1/1   1          1         1m
worker1-computation-control-server-deployment       1/1   1          1         1m
worker1-herald-daemon-deployment                    1/1   1          1         1m
worker1-liquid-legions-v2-mill-daemon-deployment    1/1   1          1         1m
worker1-requisition-fulfillment-server-deployment   1/1   1          1         1m
worker1-spanner-computations-server-deployment      1/1   1          1         1m
```

```
NAME                                     TYPE         CLUSTER-IP     EXTERNAL-IP    PORT(S)        AGE
worker1-async-computation-control-server ClusterIP    10.123.249.255 <none>         8443/TCP       1m
worker1-computation-control-server       LoadBalancer 10.123.250.81  34.134.198.198 8443:31962/TCP 1m
worker1-requisition-fulfillment-server   LoadBalancer 10.123.247.78  35.202.201.111 8443:30684/TCP 1m
worker1-spanner-computations-server      ClusterIP    10.123.244.10  <none>         8443/TCP       1m
kubernetes                               ClusterIP    10.123.240.1   <none>         443/TCP        1m
```

## Step 10. Make the Duchy accessible on the open internet

### Reserve the external IPs

There are two external APIs in the duchy. The
`worker1-requisition-fulfillment-server` (a.k.a. the public API) is called by
the EDPs to fulfill their requisitions. The `worker1-computation-control-server`
(a.k.a. the system API) is called by the other duchies to send computation
related data. As you can see from the result in the previous step. Only these
two services have external IPs. However, these external IPs are ephemeral. We
need to reserve them such that they are stable.

Go to the Gcloud [Console](https://console.cloud.google.com/networking), under
VPC network -> External IP address, find the above two external IPs, and click
RESERVE on the right.

Follow this
[link](https://cloud.google.com/compute/docs/ip-addresses/reserve-static-external-ip-address#gcloud)
if you want to reserve the IPs using Cloud CLI. Setup subdomain DNS A record
Update your domains or subdomains, one for the system API and one for the public
API, to point to the two corresponding external IPs.

For example, in the halo dev instance, we have subdomains:

-   `public.worker1.dev.halo-cmm.org`
-   `system.worker1.dev.halo-cmm.org`

The domains/subdomains are what the EDPs and other duchies use to communicate
with the duchy.

## Additional setting you may want to make

After finishing the above steps, we have

-   1 system API, 1 public API and 2 internal API running.
-   Only gRPC requests are allowed and connections are via mTLS.
-   All communications between pods within the cluster are also encrypted via
    mTLS.
-   Network policy is set such that
    -   only the system API and public API are accessible via the external IP
    -   only the Internal API is allowed to send requests outside (We plan to
        restrict the target to only Cloud Spanner, not down yet).

In this section, we list some additional settings/configurations you may want to
consider. They are mostly for enhancing security.

### 1. Application-layer secrets

encryption Those certifications and configurations we stored in Kubernetes
secret are encrypted on the storage layer, but not on the application layer. In
other works, whoever has access to the cluster resource can just call

```shell
kubectl get secrets secret_name -o json
```

to see the content of the files in the secret.

This may not be an issue if there are only a small number of people that have
access to the cluster resources. These people should already have access to
those secret files if they need to be able to create them.

However, if we want, we can enable Application-layer secrets encryption in the
cluster.

-   Go to Console -> Kubernetes Engine ->
    [Clusters](https://console.cloud.google.com/kubernetes/list)
-   Open the cluster you want to config Under Security,
-   edit the "Application-layer secrets encryption"

Note that you need to enable
[Cloud KMS](https://console.cloud.google.com/security/kms) in your GCP project
and create a private key for encrypting the secret. You also need to grant the
service account "cloudkms.cryptoKeyEncrypterDecrypter" role in the Console ->
[IAM & Admin](https://console.cloud.google.com/iam-admin) page. Check the
"include Google-provided role grants" to see the service account you are looking
for.

(Note: Whether this part works or not is not confirmed yet.)

### 2. Role Based Access Control

You can use both IAM and Kubernetes
[RBAC](https://kubernetes.io/docs/reference/access-authn-authz/rbac/) to control
access to your GKE cluster. GCloud provides the "Google Groups for RBAC"
feature. Follow this
[instruction](https://cloud.google.com/kubernetes-engine/docs/how-to/role-based-access-control)
if you want to set it up.

## Q/A

### Q1. How to generate the duchy TLS Certificate?

A: You are free to use whatever tools. One option is to use the `openssl CLI`
following these steps.

1.  install the latest openssl. for example, 3.0.1 on MAC or 1.1.1l on linux.
2.  run the following commands

    ```shell
    openssl req -out test_root.pem -new
    -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout test_root.key
    -x509 -days 3650 -subj '/O=Some Organization/CN=Some CA' -extensions v3_ca
    -addext subjectAltName=DNS:ca.someorg.example.com
    ```

    The above command will create two files.

    -   test_root.key: the private key of the root certificate
    -   test_root.pem: the public key of the root certificate
        -   You can run `openssl x509 -in test_root.pem -text -noout` to check
            the information within the certificate.

3.  Then create a file named `test_user.cnf` with the following content

    ```shell
    [usr_cert]
    basicConstraints=CA:FALSE
    authorityKeyIdentifier=keyid:always,issuer
    subjectKeyIdentifier=hash
    keyUsage=nonRepudiation,digitalSignature,keyEncipherment
    subjectAltName=DNS:server.someorg.example.com
    ```

4.  Then run two commands:

    ```shell
    openssl req -out test_user.csr -new -newkey ec -pkeyopt
    ec_paramgen_curve:prime256v1 -nodes -keyout test_user.key -subj '/O=Some
    Organization/CN=Some Server'

    openssl x509 -in test_user.csr -out test_user.pem
    -days 365 -req -CA test_root.pem -CAkey test_root.key -CAcreateserial -extfile
    test_user.cnf -extensions usr_cert
    ```

5.  The first command will generate a user key `test_user.key`, the second
    command will generate a user certificate with the above root certificate and
    user key `test_user.pem`. This test_user certificate can be either used in
    TLS connection or consent signature signing.

Or you can use the bazel tools the halo team created following these steps.

1.  checkout the
    [common-jvm](https://github.com/world-federation-of-advertisers/common-jvm)
    repo.
2.  open the
    [build/openssl/BUILD.bazel](https://github.com/world-federation-of-advertisers/common-jvm/blob/main/build/openssl/BUILD.bazel)
    file
3.  modify the attributes in the generate_root_certificate and
    generate_user_certificate targets accordingly.
4.  run `bazel build build/openssl/...`
5.  The cert files will be exported to the bazel-out directory, e.g.
    `bazel-out/k8-fastbuild/bin/build/openssl` or `bazel-bin/build/openssl`
    depending on your OS.

### Q2. How to test if the duchy is working properly?

Follow the
["How to complete multi-cluster correctnessTest on GKE"](correctness-test.md)
doc and complete a correctness test using the duchy you have deployed.