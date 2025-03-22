import { Tabs, TabList, TabPanels, Tab, TabPanel } from '@chakra-ui/react'
import Systems from './Systems'
import HostGroups from './HostGroups'
import Targets from './Targets'
import { useState } from 'react'


export default function RootComponents() {

    const [tabIndex, setTabIndex] = useState(+(localStorage.getItem("tabIndex")||0))

    const onChangeTabIndex=(index)=>{
        localStorage.setItem("tabIndex",index+"");
        setTabIndex(index);

    }
    return <Tabs index={tabIndex} onChange={(index) => onChangeTabIndex(index)} colorScheme='teal' className='dark:text-gray-400 dark:bg-slate-900 mt-4 rounded-xl px-[20px]'>
        <TabList>
            <Tab>Source Systems</Tab>
            <Tab>Target Systems</Tab>
            <Tab>Host Groups</Tab>

           
        </TabList>

        <TabPanels>
            
            <TabPanel >
                <Systems></Systems>

            </TabPanel>
            <TabPanel>
                <Targets></Targets>
            </TabPanel>
            <TabPanel>
                <HostGroups></HostGroups>
            </TabPanel>
            



        </TabPanels>
    </Tabs>
}