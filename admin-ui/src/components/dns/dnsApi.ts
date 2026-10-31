import axios from "axios";

/** Admin API call for the DNS pages: `${host}/dns/<path>?scaleguard=true` with the session token. */
export function dnsCall(auth: any, method: "get" | "post" | "delete", path: string, body?: any, query = "") {
    return axios.request({
        method,
        url: `${auth.data.host}${path}?scaleguard=true${query}`,
        data: body,
        headers: { Authorization: auth.data.token },
    });
}

export const errorText = (e: any, fallback: string): string => e?.response?.data?.error || e?.response?.data?.message || fallback;
