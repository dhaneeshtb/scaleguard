import React, { useEffect, useState } from 'react';
import { Input, Button, Table, Tbody, Tr, Td, Th, Thead, Modal, ModalOverlay, ModalContent, ModalHeader, ModalBody, ModalFooter, useDisclosure, Text } from '@chakra-ui/react';
import { v4 as uuidv4 } from 'uuid';
import axios from 'axios';
import { useAuth } from '../../contexts/AuthContext';

type Client = {
  id: string;
  name: string;
  appid: string;
  clientid: string;
  clientsecret: string;
  expiry: number;
};

type App = {
  id: string;
  name: string;
  description: string;
  clients: Client[];
};

export default function AppManagement() {
  const [apps, setApps] = useState<App[]>([]);
  const [appName, setAppName] = useState<string>('');
  const [appDescription, setAppDescription] = useState<string>('');
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [selectedAppId, setSelectedAppId] = useState<string | null>(null);
  const [clientId, setClientId] = useState<string>('');
  const [clientName, setClientName] = useState<string>('');

  const [clientSecret, setClientSecret] = useState<string>('');
  const [expiry, setExpiry] = useState<number>(30);
  const { isOpen, onOpen, onClose } = useDisclosure();
  const [deleteTarget, setDeleteTarget] = useState<{ type: 'app' | 'client'; appId?: string; clientId?: string } | null>(null);
  const [errorMessage, setErrorMessage] = useState<string>('');


  const { auth } = useAuth() as any;

    const [isLoading,setLoading] =useState(false);

    const loadApps=async ()=>{
       const r=await axios.get(`${auth.data.host}/apps?scaleguard=true`, {
            headers: {
                Authorization: auth.data.token
            }
        })
        setApps(r.data);
    }
    const saveApp=async (app)=>{
        const r=await axios.post(`${auth.data.host}/apps?scaleguard=true`,app, {
             headers: {
                 Authorization: auth.data.token
             }
         })
        await loadApps();
     }

     const deleteApp=async (appid)=>{
        const r=await axios.delete(`${auth.data.host}/apps/${appid}?scaleguard=true`, {
             headers: {
                 Authorization: auth.data.token
             }
         })
        await loadApps();
     }

    
     const saveClient=async (client)=>{
         const r=await axios.post(`${auth.data.host}/clients?scaleguard=true`,client, {
              headers: {
                  Authorization: auth.data.token
              }
          })
         await loadApps();
      }
 
      const deleteClient=async (clientid)=>{
         const r=await axios.delete(`${auth.data.host}/clients/${clientid}?scaleguard=true`, {
              headers: {
                  Authorization: auth.data.token
              }
          })
         await loadApps();
      }

    useEffect(() => {
            loadApps();
    }, [auth])


  const createApp =async () => {
    if (!appName.trim() || !appDescription.trim()) {
      setErrorMessage('App Name and Description are required.');
      return;
    }
    setErrorMessage('');
    const newApp: App = {
      id: uuidv4(),
      name: appName,
      description: appDescription,
      clients: []
    };
    await saveApp(newApp);
    // setApps([...apps, newApp]);
    setAppName('');
    setAppDescription('');
  };

  const openAddClientModal = (appId: string) => {
    setSelectedAppId(appId);
    setClientName('');
    setClientId(uuidv4());
    setClientSecret(uuidv4());
    setExpiry(30);
    setIsModalOpen(true);
  };

  const addClient = async() => {
    if (!selectedAppId) return;
    const expiryDay=new Date().getTime()+(expiry*60*60*24*1000)
    const newClient: Client = {
      id: clientId,
      name:clientName,
      appid:selectedAppId,
      clientid:clientId,
      clientsecret: clientSecret,
      expiry: expiryDay,
    };
    await saveClient(newClient);
    // setApps(apps.map(app => app.id === selectedAppId ? { ...app, clients: [...app.clients, newClient] } : app));
    setIsModalOpen(false);
  };

  const confirmDelete = (type: 'app' | 'client', appId?: string, clientId?: string) => {
    setDeleteTarget({ type, appId, clientId });
    onOpen();
  };

  const deleteItem = async () => {
    if (!deleteTarget) return;
    if (deleteTarget.type === 'app' && deleteTarget.appId) {

      await deleteApp(deleteTarget.appId);
      //setApps(apps.filter(app => app.id !== deleteTarget.appId));
    } else if (deleteTarget.type === 'client' && deleteTarget.appId && deleteTarget.clientId) {
        await deleteClient(deleteTarget.clientId);

    }
    onClose();
  };

  return (
    <div className="p-6">
      <h2 className="text-2xl font-bold mb-4">App Management</h2>
      <div className="mb-4 space-y-2">
        <Input placeholder="App Name" value={appName} onChange={(e) => setAppName(e.target.value)} />
        <Input placeholder="App Description" value={appDescription} onChange={(e) => setAppDescription(e.target.value)} />
        {errorMessage && <Text color="red.500">{errorMessage}</Text>}
        <Button onClick={createApp} colorScheme="blue">Create App</Button>
      </div>
      <Table variant="simple">
        <Thead>
          <Tr>
            <Th>App Name</Th>
            <Th>Description</Th>
            <Th>Clients</Th>
            <Th>Actions</Th>
          </Tr>
        </Thead>
        <Tbody>
          {apps.map((app) => (
            <Tr key={app.id}>
              <Td>{app.name}</Td>
              <Td>{app.description}</Td>
              <Td>
                {app.clients && app.clients.map(client => (
                  <div key={client.id} className="mb-2">
                    <p><strong>Client Name:</strong> {client.name}</p>
                    <p><strong>Client ID:</strong> {client.clientid}</p>
                    <p><strong>Client Secret:</strong> {client.clientsecret}</p>
                    <p><strong>Expiry Date:</strong> {client.expiry === 0 ? 'No Expiry' : new Date(client.expiry).toLocaleDateString()}</p>
                    <Button colorScheme="red" size="xs" onClick={() => confirmDelete('client', app.id, client.id)}>Delete</Button>
                  </div>
                ))}
              </Td>
              <Td>
                <Button onClick={() => openAddClientModal(app.id)} colorScheme="green">Add Client</Button>
                <Button colorScheme="red" ml={2} onClick={() => confirmDelete('app', app.id)}>Delete App</Button>
              </Td>
            </Tr>
          ))}
        </Tbody>
      </Table>
      <Modal isOpen={isModalOpen} onClose={() => setIsModalOpen(false)}>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Add Client</ModalHeader>
          <ModalBody>
            <label className="block text-gray-700 text-sm font-bold mb-2">Client Name</label>
            <Input value={clientName} onChange={e=>setClientName(e.target.value)} />
            <label className="block text-gray-700 text-sm font-bold mb-2">Client ID</label>
            <Input value={clientId} isReadOnly />
            <label className="block text-gray-700 text-sm font-bold mt-2">Client Secret</label>
            <Input value={clientSecret} isReadOnly />
            <label className="block text-gray-700 text-sm font-bold mt-2">Expiry (Days, 0 for No Expiry)</label>
            <Input type="number" value={expiry} onChange={(e) => setExpiry(Number(e.target.value))} />
          </ModalBody>
          <ModalFooter>
            <Button onClick={addClient} colorScheme="blue">Add</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
      <Modal isOpen={isOpen} onClose={onClose}>
        <ModalOverlay />
        <ModalContent>
          <ModalHeader>Confirm Deletion</ModalHeader>
          <ModalBody>Are you sure you want to delete this {deleteTarget?.type}?</ModalBody>
          <ModalFooter>
            <Button onClick={deleteItem} colorScheme="red">Delete</Button>
            <Button onClick={onClose} ml={2}>Cancel</Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}