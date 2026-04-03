import { Tabs, TabList, TabPanels, Tab, TabPanel } from '@chakra-ui/react'
import Systems from './Systems'
import HostGroups from './HostGroups'
import Targets from './Targets'
import { useState } from 'react'
import { FaPlug, FaServer, FaNetworkWired } from 'react-icons/fa'

export default function RootComponents({ initialData }: { initialData?: { sourcesystems?: any[]; targetsystems?: any[]; hostgroups?: any[] } }) {

    const [tabIndex, setTabIndex] = useState(+(localStorage.getItem("tabIndex") || 0))

    const onChangeTabIndex = (index) => {
        localStorage.setItem("tabIndex", index + "");
        setTabIndex(index);
    }

    const tabs = [
        { label: "Source Systems", icon: <FaServer className="text-xs" /> },
        { label: "Target Systems", icon: <FaPlug className="text-xs" /> },
        { label: "Host Groups", icon: <FaNetworkWired className="text-xs" /> },
    ];

    return (
        <div className="bg-white/5 dark:bg-slate-800/40 backdrop-blur-sm rounded-2xl border border-slate-200/60 dark:border-slate-700/50 overflow-hidden">
            <Tabs
                index={tabIndex}
                onChange={(index) => onChangeTabIndex(index)}
                colorScheme='teal'
                className='dark:text-gray-400'
            >
                <TabList className="px-4 pt-3 border-b border-slate-200/40 dark:border-slate-700/40">
                    {tabs.map((tab, i) => (
                        <Tab key={i} className="flex items-center gap-2 !text-xs !font-medium !uppercase !tracking-wider">
                            {tab.icon}
                            {tab.label}
                        </Tab>
                    ))}
                </TabList>

                <TabPanels>
                    <TabPanel>
                        <Systems initialData={initialData?.sourcesystems} />
                    </TabPanel>
                    <TabPanel>
                        <Targets initialData={initialData?.targetsystems} />
                    </TabPanel>
                    <TabPanel>
                        <HostGroups initialData={initialData?.hostgroups} />
                    </TabPanel>
                </TabPanels>
            </Tabs>
        </div>
    );
}