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
    "autoProcure":true
}
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
            cschema = targetSchema;
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
            cschema = null;
            break;
        case "hostgroups":
            cschema = null;
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
      element.json = JSON.parse(element.json);
    });
    return data;
  }