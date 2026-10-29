import { useParams } from "react-router-dom";
import EntityEditor from "../components/EntityEditor";

const ManageSource = () => {
    const { id } = useParams() as any;
    return <EntityEditor key={`sourcesystems:${id}`} type="sourcesystems" id={id} />;
};

export default ManageSource;
