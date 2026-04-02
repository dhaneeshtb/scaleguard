import axios from "axios";



const hostgroupSchema = {
    "scheme": "https",
    "host": "",
    "port": "443",
    "health": "",
    "priority": 0,
    "type": "active",
    "groupId": "hostgroup1",
    "loadFactor": 0.0,
    "active": true
}


const sourceSystemSchemaDef ={
    "scheme": {
      "displayName": "Scheme",
      "description": "The protocol used for communication.",
      "hint": "Example: https or http",
      "mandatory":true
    },
    "host": {
      "displayName": "Host",
      "description": "The hostname or IP address of the server.",
      "hint": "Example: localhost or example.com",
      "mandatory":true
    },
    "port": {
      "displayName": "Port",
      "description": "The port number on which the server is running.",
      "hint": "Example: 80, 443",
      "mandatory":true
    },
    "basePath": {
      "displayName": "Base Path",
      "description": "The base path for API endpoints.",
      "hint": "Example: /",
      "mandatory":true
    },
    "name": {
      "displayName": "Name",
      "description": "A unique name for the configuration.",
      "hint": "Example: My API Gateway",
      "mandatory":true
    },
    "certificateId": {
      "displayName": "Certificate ID",
      "description": "ID of the SSL certificate used for secure communication.",
      "hint": "Example: cert-12345",
      "mandatory":false

    },
    "groupId": {
      "displayName": "Group ID",
      "description": "An identifier for grouping related configurations.",
      "hint": "Example: group-001",
      "mandatory":false
    },
    "target": {
      "displayName": "Target Group Id",
      "description": "The target backend service or API endpoint.",
      "hint": "Example: https://backend.example.com",
      "mandatory":true
    },
    "async": {
      "displayName": "Async",
      "description": "Specifies whether the request should be asynchronous.",
      "hint": "true for async processing, false for synchronous",
      "mandatory":false
    },
    "callbackId": {
      "displayName": "Callback ID",
      "description": "An identifier for callback handling in async requests.",
      "hint": "Example: cb-98765",
      "mandatory":false
    },
    "jwtKeylookup": {
      "displayName": "JWT Key Lookup",
      "description": "JWT key lookup configuration for authentication.",
      "hint": "Example: Public key reference",
      "mandatory":false

    },
    "autoProcure": {
      "displayName": "Auto Procure Certificate",
      "description": "Determines if the system should automatically procure required resources.",
      "hint": "Automatically procure and configure certificates",
      "mandatory":false
    }
  }
  
  

const sourceSystemSchema = {
    "scheme": "https",
    "host": "localhost",
    "secappid":"",
    "port": "80",
    "basePath": "/",
    "name": "",
    "certificateId":"",
    "groupId": "",
    "target": "",
    "async": false,
    "callbackId": "",
    "jwtKeylookup": null,
    "autoProcure":true,
    "asyncEngine":null
}
const targetSystemSchemaDef = {
    "scheme": {
      "displayName": "Scheme",
      "description": "The protocol used for upstream communication.",
      "hint": "Example: http or https",
      "mandatory": true
    },
    "hostGroupId": {
      "displayName": "Host Group ID",
      "description": "The host group to route traffic to.",
      "hint": "Example: h-my-service",
      "mandatory": true
    },
    "basePath": {
      "displayName": "Base Path",
      "description": "The base path prefix for upstream routing.",
      "hint": "Example: / or /api/v1",
      "mandatory": true
    },
    "name": {
      "displayName": "Name",
      "description": "A descriptive name for this target system.",
      "hint": "Example: my-api-backend",
      "mandatory": true
    },
    "groupId": {
      "displayName": "Group ID",
      "description": "An identifier for grouping related targets.",
      "hint": "Example: t-my-service",
      "mandatory": false
    },
    "includeHeaders": {
      "displayName": "Include Headers",
      "description": "Headers to include when forwarding to upstream.",
      "hint": "JSON object of header key-value pairs",
      "mandatory": false
    },
    "excludeHeaders": {
      "displayName": "Exclude Headers",
      "description": "Headers to exclude when forwarding to upstream.",
      "hint": "JSON object of header keys to strip",
      "mandatory": false
    },
    "cachedResources": {
      "displayName": "Cached Resources",
      "description": "URL patterns to cache at the proxy level.",
      "hint": "JSON array of URL patterns",
      "mandatory": false
    },
    "enableCache": {
      "displayName": "Enable Cache",
      "description": "Enable response caching for this target.",
      "hint": "true to enable caching",
      "mandatory": false
    }
};

const hostgroupSchemaDef = {
    "scheme": {
      "displayName": "Scheme",
      "description": "The protocol used for health checks and routing.",
      "hint": "Example: https or http",
      "mandatory": true
    },
    "host": {
      "displayName": "Host",
      "description": "The hostname or IP of the upstream server.",
      "hint": "Example: backend.example.com",
      "mandatory": true
    },
    "port": {
      "displayName": "Port",
      "description": "The port number of the upstream server.",
      "hint": "Example: 443, 8080",
      "mandatory": true
    },
    "health": {
      "displayName": "Health Check Path",
      "description": "Endpoint to check upstream server health.",
      "hint": "Example: /health or /ping",
      "mandatory": false
    },
    "priority": {
      "displayName": "Priority",
      "description": "Routing priority (higher = preferred).",
      "hint": "Example: 10",
      "mandatory": false
    },
    "type": {
      "displayName": "Type",
      "description": "Whether this host is active or standby.",
      "hint": "active for primary, standby for failover",
      "mandatory": true
    },
    "groupId": {
      "displayName": "Group ID",
      "description": "The host group this server belongs to.",
      "hint": "Example: h-my-service",
      "mandatory": true
    },
    "loadFactor": {
      "displayName": "Load Factor",
      "description": "Weight for load balancing within the group.",
      "hint": "Example: 0.5 (0 to 1.0)",
      "mandatory": false
    },
    "active": {
      "displayName": "Active",
      "description": "Whether this host group member is enabled.",
      "hint": "true to enable, false to disable",
      "mandatory": false
    }
};

const targetSchema = {
    "scheme": "https",
    "hostGroupId": "",
    "basePath": "/",
    "name": "",
    "groupId": "",
    "includeHeaders": {},
    "excludeHeaders": {},
    "cachedResources": [],
    "enableCache": true,
}

export const getBaseSchema = (type) => {
    let cschema: any = null;
    switch (type) {
        case "sourcesystems":
            cschema = sourceSystemSchema;
            break;
        case "targetsystems":
            cschema = {...targetSchema};
            break;
        case "hostgroups":
            cschema = hostgroupSchema;
            break;
        default:
            cschema = null;
            break;

    }
    return cschema;
}


export const getBaseSchemaDef = (type) => {
    let cschema: any = null;
    switch (type) {
        case "sourcesystems":
            cschema = sourceSystemSchemaDef;
            break;
        case "targetsystems":
            cschema = targetSystemSchemaDef;
            break;
        case "hostgroups":
            cschema = hostgroupSchemaDef;
            break;
        default:
            cschema = null;
            break;

    }
    return cschema;
}


const pruneObject = (object, type) => {
    const schema = getBaseSchema(type);
    const to: any = {};
    Object.keys(schema).map(s => {
        to[s] = object[s];
    })
    to.id = object.id;
    if(to.scheme=="https" && to.port=="80"){
        to.port="443";
    }
    return to;
}

export const updateSource=async (baseObject,type,auth)=>{

    await axios.post(`${auth.data.host}/config/${type}?scaleguard=true`,  pruneObject(baseObject, type),{
        headers:{
            Authorization:auth.data.token
        }
    });

}
export const renewCertificate=async (sourceId,auth)=>{

    await axios.post(`${auth.data.host}/config/renewcert/${sourceId}?scaleguard=true`, {},{
        headers:{
            Authorization:auth.data.token
        }
    });

}


export const deleteSource=async (type,id,auth)=>{

    await axios.delete(`${auth.data.host}/config/${type}/${id}?scaleguard=true`,{
        headers:{
            Authorization:auth.data.token
        }
    });

}
export const formatData = (data) => {
    data.forEach(element => {
      try {
        element.json = JSON.parse(element.json);
      } catch (e) {
        console.error("Failed to parse JSON for element:", element.id, e);
        element.json = {};
      }
    });
    return data;
  }