import {
  Button, IconButton, Link,
  Modal,
  ModalOverlay,
  ModalContent,
  ModalHeader,
  ModalFooter,
  ModalBody,
  ModalCloseButton,
  useDisclosure,
  Heading
} from '@chakra-ui/react';
import axios from 'axios';
import React, { useEffect, useState } from 'react';
import {
  FaArrowCircleDown, FaArrowCircleUp, FaCheck, FaClipboard, FaEdit, FaFileDownload, FaInfoCircle, FaPlusCircle, FaSave, FaTrash,
} from "react-icons/fa";
import { CopyToClipboard } from 'react-copy-to-clipboard';
import { useToast } from '@chakra-ui/react'
import { useAuth } from '../contexts/AuthContext';
import DeleteSystem from './DeleteSystem';
import { formatData } from './sourceupdate';


export default function DNS() {

  const [systems, setSystems] = useState<any>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const { auth } = useAuth() as any;

  

  const load = async () => {
    const r = await axios.get(auth.data.host + "/certificates?scaleguard=true", {
      headers: {
        Authorization: auth.data.token
      }
    });
    setSystems(formatData(r.data))
  }
  useEffect(() => {
    load();

  }, [])

  const deleteItem = async (id) => {
    setLoading(true)
    await axios.delete(auth.data.host + "/certificates/" + id + "?scaleguard=true", {
      headers: {
        Authorization: auth.data.token
      }
    })
    await load()
    setLoading(false);

  }
  const verify = async (id) => {
    setLoading(true)
    await axios.get(auth.data.host + "/certificates/" + id + "/verify?scaleguard=true", {
      headers: {
        Authorization: auth.data.token
      }
    })
    await load()
    setLoading(false);

  }





  function NewCertificate({ onUpdate }) {
    const { isOpen, onOpen, onClose } = useDisclosure()
    const [domainNames, setDomainNames] = useState<string>("");
    const [saving, setSaving] = useState<boolean>(false);


    const onCreate = async () => {
      setSaving(true)
      await axios.post(auth.data.host + "/certificates?scaleguard=true", {
        domainNames: domainNames.split(",")
      }, {
        headers: {
          Authorization: auth.data.token
        }
      })
      await onUpdate()
      setSaving(false);
      onClose();

    }

    return (
      <>
        <Button mt="2" onClick={onOpen} leftIcon={<FaPlusCircle></FaPlusCircle>} variant={"outline"} colorScheme='teal' size={"xs"}>Certificate</Button>


        <Modal isOpen={isOpen} onClose={onClose}>
          <ModalOverlay />
          <ModalContent className='dark:bg-slate-900 dark:text-white shadow-3xl'>
            <ModalHeader>Request Certificate</ModalHeader>
            <ModalCloseButton />
            <ModalBody className='dark:text-white'>
              <label className="block text-sm font-bold mb-1">
                Domain Names (Comma separated if multiple)
              </label>
              <input value={domainNames} onChange={(e) => setDomainNames(e.target.value)} className="dark:bg-black shadow appearance-none border rounded w-full py-2 px-1 " />

            </ModalBody>

            <ModalFooter className='dark:text-white'>
              <Button colorScheme='blue' mr={3} onClick={onClose}>
                Close
              </Button>
              <Button isLoading={saving} variant='outline' colorScheme='teal' onClick={onCreate}>Save</Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
      </>
    )
  }


  function DnsChallenge({ info }) {
    const { isOpen, onOpen, onClose } = useDisclosure()
    return (
      <>
        <Button onClick={onOpen} leftIcon={<FaInfoCircle></FaInfoCircle>}>DNS Challenge Info</Button>

        <Modal isOpen={isOpen} onClose={onClose}>
          <ModalOverlay />
          <ModalContent className='dark:bg-slate-900 dark:text-white shadow-3xl'>
            <ModalHeader>Auth Challenge Info</ModalHeader>
            <ModalCloseButton />
            <ModalBody>
              <div>
                <ModalHeader>HTTP Challenge Info</ModalHeader>

                <code className='w-full text-gray-400 text-xs break-words	'> {info.httpChallenge.message}</code>
              </div>
              <div>
                <ModalHeader>DNS Challenge Info</ModalHeader>

                {info.dnsChallenge && <><code className='w-full text-gray-400 text-xs break-words	'> {info.dnsChallenge.message}</code>

                  <table className='table-auto m-[20px] border-collapse border border-slate-400'>
                    <thead>
                      <tr>
                        <th className='text-left text-gray-500 border border-slate-300 px-3'>Type</th>
                        <th className='text-left text-gray-500 border border-slate-300 px-3'>Name</th>
                        <th className='text-left text-gray-500 border border-slate-300 px-3'>Content</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr>
                        <td className='text-xs text-left border border-slate-300 px-3'>{info.dnsChallenge.dnsRecordType}</td>
                        <td className='text-xs text-left break-all border border-slate-300 px-3'>{info.dnsChallenge.domainRecordName}</td>
                        <td className='text-xs text-left break-all border border-slate-300 px-3'>{info.dnsChallenge.dnsRecordValue}</td>
                      </tr>
                    </tbody>
                  </table>

                </>
                }
              </div>



            </ModalBody>
            <ModalFooter>
              <Button colorScheme='blue' mr={3} onClick={onClose}>
                Close
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
      </>
    )
  }
  function HttpChallenge({ id }) {
    const { isOpen, onOpen, onClose } = useDisclosure()

    const [certificate, setCertificate] = useState<any>({});
    const [loading, setLoading] = useState<boolean>(false);
    const toast = useToast()
    const filePrivateKey = new Blob([certificate.privateKey], { type: 'text/plain' });
    const fileCertificate = new Blob([certificate.certificate], { type: 'text/plain' });



    const alertit = (keyType) => {
      toast({
        title: keyType + ' Copied.',
        description: "copied " + keyType + " to clipboard",
        status: 'success',
        position: "top",
        duration: 9000,
        isClosable: true,
      })
    }

    const load = async () => {
      try {
        const r = await axios.get(auth.data.host + "/certificates/" + id + "/download?scaleguard=true", {
          headers: {
            Authorization: auth.data.token
          }
        });
        setCertificate((r.data))
      } catch (e) {

      }
    }
    const openView = async () => {
      await load();
      onOpen();
    }
    useEffect(() => {
      // load();

    }, [id])
    return (
      <>
        <Button size={"sm"} variant={"outline"} colorScheme='gray.400' isLoading={loading} onClick={openView} leftIcon={<FaInfoCircle></FaInfoCircle>}>Download</Button>

        <Modal isOpen={isOpen} onClose={onClose} size={"xl"}>
          <ModalOverlay />
          <ModalContent className='dark:bg-slate-900 dark:text-white shadow-3xl'>
            <ModalHeader>Download</ModalHeader>
            <ModalCloseButton />
            <ModalBody>

              <Heading className='flex gap-2 justify-between'>Private Key

                <div className='flex gap-2'>

                  <a className='text-sm' download="private.key" target="_blank" rel="noreferrer" href={URL.createObjectURL(filePrivateKey)} style={{
                    textDecoration: "inherit",
                    color: "inherit",
                  }}><IconButton variant={"outline"} size={"sm"} colorScheme='green' aria-label='' icon={<FaFileDownload></FaFileDownload>} ></IconButton></a>
                  <CopyToClipboard text={certificate.privateKey} onCopy={() => alertit("Private Key")}>


                    <IconButton aria-label='' icon={<FaClipboard></FaClipboard>} colorScheme='green' size={"sm"} variant={"outline"}></IconButton>

                  </CopyToClipboard></div></Heading>

              <textarea value={certificate.privateKey} className='w-full text-black dark:text-white dark:bg-slate-700 h-[300px] text-xs' disabled>

              </textarea>
              <Heading className='flex gap-2 justify-between'>Certificate
                <div className='flex gap-2'>

                  <a className='text-sm' download="server.crt" target="_blank" rel="noreferrer" href={URL.createObjectURL(fileCertificate)} style={{
                    textDecoration: "inherit",
                    color: "inherit",
                  }}><IconButton variant={"outline"} size={"sm"} colorScheme='green' aria-label='' icon={<FaFileDownload></FaFileDownload>} ></IconButton></a>


                  <CopyToClipboard text={certificate.certificate} onCopy={() => alertit("Certificate")}>

                    <IconButton aria-label='' icon={<FaClipboard></FaClipboard>} colorScheme='green' size={"sm"} variant={"outline"}></IconButton>

                  </CopyToClipboard>
                </div>

              </Heading>
              <textarea value={certificate.certificate} className='w-full dark:bg-slate-700 h-[300px] text-xs' disabled>

              </textarea>

            </ModalBody>

            {/* <ModalFooter>
              <Button colorScheme='blue' mr={3} onClick={onClose}>
                Close
              </Button>
            </ModalFooter> */}
          </ModalContent>
        </Modal>
      </>
    )
  }
  return (
    <div className="flex flex-col dark:bg-slate-900 ">
      

<div className="bg-black flex justify-center items-center mt-1">
	<div className="container mx-auto bg-indigo-800 dark:bg-indigo-900 rounded-lg p-14">
		<div>
			<h1 className="text-center font-bold text-white text-4xl">Set your dns base domain name</h1>
				<p className="mx-auto font-normal text-sm my-6 max-w-lg">Enter your select domain name and choose any
					extension name in the next step (choose between .com, .online, .tech, .site, .net, and more)</p>
				<div className="sm:flex items-center bg-white rounded-lg overflow-hidden px-2 py-1 justify-between gap-2">
					<input className="text-base text-gray-400 flex-grow rounded-lg border-2 px-2 py-2" type="text" placeholder="DNS base domain name" />
          <input className="text-base text-gray-400 flex-grow rounded-lg border-2 px-2 py-2" type="text" placeholder="IP Address" />
					<div className="ms:flex items-center rounded-lg">
						<select id="type" className="text-base text-gray-800 outline-none border-2 px-4 py-2 rounded-lg">
            <option value="base" selected>base</option>
            <option value="record">record</option>
          </select>
					</div>
          <div className="items-center rounded-lg">
          <input className="text-base text-gray-400  rounded-lg border-2 px-2 py-2" type="text" placeholder="TTL" />
          </div>
          <Button leftIcon={<FaSave></FaSave>} bg={"bg-indigo-500"} className="bg-indigo-500 text-white text-base rounded-lg px-4 py-2 font-thin">Save</Button>

				</div>
		</div>
	</div>
</div>
<div className='flex gap-2 justify-end px-[20px]'>

        <NewCertificate onUpdate={load}></NewCertificate>


      </div>
      <div className="overflow-x-auto sm:-mx-6 lg:-mx-8">
        <div className="inline-block min-w-full py-2 sm:px-6 lg:px-8">
          <div className="overflow-hidden">
            <table className="min-w-full text-left text-sm font-light dark:text-white">
              <thead className="border-b font-medium dark:border-neutral-500">
                <tr>
                  <th scope="col" className="px-6 py-4 ">#id</th>
                  <th scope="col" className="px-6 py-4">Status</th>
                  <th scope="col" className="px-6 py-4">Expiry Time</th>
                  <th scope="col" className="px-6 py-4">Domains</th>
                  <th scope="col" className="px-6 py-4">Auth Keys</th>
                  <th scope="col" className="px-6 py-4">Download Certs</th>
                  <th scope="col" className="px-6 py-4">Action</th>
                </tr>
              </thead>
              <tbody>

                {
                  systems && systems.map((system: any) => {
                    return <tr
                      className="border-b transition duration-300 ease-in-out hover:bg-neutral-100 dark:border-neutral-500 dark:hover:bg-neutral-600">
                      <td className="px-6 py-4 font-medium">{system.id}</td>
                      <td className=" px-6 py-4  "><div className='flex gap-2 uppercase justify-center'>{system.json.status} {system.json.status == "valid" ? <FaArrowCircleUp color='green'></FaArrowCircleUp> : <FaArrowCircleDown color='red'></FaArrowCircleDown>}</div></td>
                      <td className=" px-6 py-4">{system.json.status == "valid" ? new Date(system.expiryTime).toLocaleString() : system.json.expires}</td>
                      <td className="px-6 py-4">{system.json.identifiers.map(s => s.value).join(",")}</td>
                      <td className=" px-6 py-4">{system.json.status == "valid" ? "Validated" : <DnsChallenge info={system}></DnsChallenge>}</td>
                      <td className=" px-6 py-4"><HttpChallenge id={system.id}></HttpChallenge></td>
                      <td><div className=" px-6 py-4 flex gap-2 justify-center items-center">
                      {system.json.status != "valid" && <Button isLoading={loading} onClick={() => verify(system.id)} colorScheme='green' leftIcon={<FaCheck></FaCheck>} variant={"outline"} size={"xs"}>Verify</Button> }
                        
                        <DeleteSystem id={system.id} source={"Certificate"} onAction={()=>deleteItem(system.id) as any} buttonType='big'></DeleteSystem>
                        {/* <Button isLoading={loading} onClick={() => deleteItem(system.id)} colorScheme='red' leftIcon={<FaTrash></FaTrash>} variant={"outline"} size={"xs"}>Delete</Button> */}
                      </div> </td>
                    </tr>
                  })
                }


              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  );
}