import { useParams } from "react-router-dom";
import EntityEditor, { EntityType } from "../components/EntityEditor";

const ManageHost = () => {
    const { id, type } = useParams() as any;
    const t: EntityType = type === "sourcesystems" || type === "targetsystems" ? type : "hostgroups";
    return <EntityEditor key={`${t}:${id}`} type={t} id={id} />;
};

export default ManageHost;
