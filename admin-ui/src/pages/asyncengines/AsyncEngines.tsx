import React, { useEffect, useState } from "react";
import { Box, Table, Thead, Tbody, Tr, Th, Td, Text, VStack, Button, IconButton, Input, Select, useToast } from "@chakra-ui/react";
import { EditIcon, DeleteIcon } from "@chakra-ui/icons";
import { useAuth } from "../../contexts/AuthContext";
import axios from "axios";

interface Queue {
    id?:string,
  name: string;
  type: "embedded" | "kafka" | string;
  brokers?: string;
  topic: string;
}

const AsyncEngines = () => {
      const { auth } = useAuth() as any;


  const [loading, setLoading] = useState<boolean>(false);

    
  const [queueData, setQueueData] = useState<Queue[]>([
  ]);
  const [newQueue, setNewQueue] = useState<Queue>({ name: "", type: "embedded", brokers: "", topic: "" });
  const [editingIndex, setEditingIndex] = useState(null);


  const toast = useToast();

  const load = async () => {
    const r = await axios.get(auth.data.host + "/asyncengines?scaleguard=true", {
      headers: {
        Authorization: auth.data.token
      }
    });
    const res=r.data;
    
    setQueueData(Object.keys(res).map(r=>{
        const o = res[r];
        return {
            id:o.id,
            name:o.name,type:o.type,topic:o.payload?.topic||"event-stream",
            brokers:o.payload.brokers,
        }
    }))
  }

  const save=async ()=>{
    setLoading(true)
    try{
    await axios.post(auth.data.host + "/asyncengines?scaleguard=true",{
      name:newQueue.name,
      id:newQueue.name,
      type:newQueue.type,
      payload:{
        topic:newQueue.topic,
        brokers:newQueue.brokers
      }
    }, {
      headers: {
        Authorization: auth.data.token
      }
    })
    setNewQueue({ type: "", brokers: "", topic: "" ,name:""});

    await load()
}catch(e){


    toast({
        title: "Update Failed!",
        status: "error",
        duration: 2000,
        isClosable: true,
      });

}
    setLoading(false);
  }



  useEffect(() => {
    load();
  }, [])

  const getQueueUrl = (queue) => {
    if (queue.type === "embedded") {
      return `${queue.type}://${queue.topic}`;
    }
    return `${queue.type}://${queue.brokers}/${queue.topic}`;
  };

  const handleAddQueue = async () => {
   await  save();
  };

  const handleEditQueue = (index) => {
    setNewQueue(queueData[index]);
    setEditingIndex(index);
  };

  const handleDeleteQueue = (index) => {
    setQueueData(queueData.filter((_, i) => i !== index));
  };

  return (
    <VStack className="text-black dark:text-white" spacing={4} align="stretch" p={5}>
      <Text fontSize="xl" fontWeight="bold" className="text-black dark:text-white">Event Queue Engine</Text>
      <Box className="text-black dark:text-white border-gray-200 dark:border-gray-600" borderWidth="1px" borderRadius="lg" p={4}>
        <Table className="text-black dark:text-white border-gray-200 dark:border-gray-600" variant="simple">
          <Thead>
            <Tr>
              <Th className="text-black dark:text-white">Engine Name</Th>
              <Th className="text-black dark:text-white">Queue Type</Th>
              <Th className="text-black dark:text-white">Queue URL</Th>
              <Th className="text-black dark:text-white">Actions</Th>
            </Tr>
          </Thead>
          <Tbody>
            {queueData.map((queue, index) => (
              <Tr key={index}>
                <Td className="text-black dark:text-white">{queue.name}</Td>
                <Td className="text-black dark:text-white">{queue.type}</Td>
                <Td className="text-black dark:text-white">{getQueueUrl(queue)}</Td>
                <Td className="text-black dark:text-white">
                  <IconButton icon={<EditIcon />} size="sm" mr={2} aria-label="Edit Queue" onClick={() => handleEditQueue(index)} />
                  <IconButton icon={<DeleteIcon />} size="sm" aria-label="Delete Queue" onClick={() => handleDeleteQueue(index)} />
                </Td>
              </Tr>
            ))}
          </Tbody>
        </Table>
      </Box>
      <Box className="text-black dark:text-white border-gray-200 dark:border-gray-600" p={4} borderWidth="1px" borderRadius="lg">
        <div className="flex justify-between items-center w-full">
        <Text fontSize="lg" fontWeight="bold">{editingIndex !== null ? "Edit Queue" : "Add Queue"}</Text>
        {editingIndex!=null? <Button className="text-black dark:text-white" rounded={"full"} colorScheme="teal" onClick={()=>{
            setEditingIndex(null)
            setNewQueue({name:"",type:"",topic:""})
        }}>Cancel</Button>:<></>
    }
    </div>

        <Select className="text-black dark:text-white" placeholder="Select Queue Type" value={newQueue.type} onChange={(e) => setNewQueue({ ...newQueue, type: e.target.value })} mt={2}>
          <option value="embedded">Embedded</option>
          <option value="kafka">Kafka</option>
        </Select>
        
        <Input className="text-black dark:text-white" placeholder="Engine Name" value={newQueue.name} onChange={(e) => setNewQueue({ ...newQueue, name: e.target.value })} mt={2} />
        <Input className="text-black dark:text-white" placeholder="Topic Name" value={newQueue.topic} onChange={(e) => setNewQueue({ ...newQueue, topic: e.target.value })} mt={2} />
        {newQueue.type == "kafka" && (
          <>
            <Input className="text-black dark:text-white" placeholder="Brokers(localhost:9092)" value={newQueue.brokers} onChange={(e) => setNewQueue({ ...newQueue, brokers: e.target.value })} mt={2} />
          </>
        )}
        <Button rounded={"full"} isLoading={loading} isDisabled={!newQueue.name||!newQueue.topic ||  (newQueue.type=="kafka"?!newQueue.brokers:false)} className="text-black dark:text-white" colorScheme="blue" onClick={handleAddQueue} mt={3}>{editingIndex !== null ? "Update Queue" : "Add Queue"}</Button>
      </Box>
    </VStack>
  );
};

export default AsyncEngines;
