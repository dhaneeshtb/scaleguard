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

const sourceSystemSchema = {
    "scheme": "https",
    "host": "localhost",
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

const pruneObject = (object, type) => {
    const schema = getBaseSchema(type);
    const to: any = {};
    Object.keys(schema).map(s => {
        to[s] = object[s];
    })
    to.id = object.id;
    return to;
}

export const updateSource=async (baseObject,type,auth)=>{

    await axios.post(`${auth.data.host}/config/${type}?scaleguard=true`,  pruneObject(baseObject, type),{
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