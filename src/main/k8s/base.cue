// Copyright 2020 The Measurement System Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package k8s

import (
	"strings"
)

#Target: {
	name:   string
	_caps:  strings.Replace(strings.ToUpper(name), "-", "_", -1)
	target: "$(" + _caps + "_SERVICE_HOST):$(" + _caps + "_SERVICE_PORT)"
}

#Port: {
	name:       string
	port:       uint16
	protocol:   "TCP" | "UDP"
	targetPort: uint16
}

#GrpcService: {
	_name:      string
	_system:    string
	apiVersion: "v1"
	kind:       "Service"
	metadata: {
		name: _name
		annotations: system: _system
	}
	spec: {
		selector: app: _name + "-app"
		type: "ClusterIP"
		ports: [{
			name:       "port"
			port:       8080
			protocol:   "TCP"
			targetPort: 8080
		}]
	}
}

#Pod: {
	_name:  string
	_image: string
	_args: [...string]
	_ports:           [{containerPort: 8080}] | *[]
	_restartPolicy:   string | *"Always"
	_imagePullPolicy: string | *"Never"
	_system:          string
	apiVersion:       "v1"
	kind:             "Pod"
	metadata: {
		name: _name + "-pod"
		labels: app:         _name + "-app"
		annotations: system: _system
	}
	spec: {
		containers: [{
			name:            _name + "-container"
			image:           _image
			imagePullPolicy: _imagePullPolicy
			args:            _args
			ports:           _ports
		}]
		restartPolicy: _restartPolicy
	}
}

#ServerPod: #Pod & {
	_ports: [{containerPort: 8080}]
}