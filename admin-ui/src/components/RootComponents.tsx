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
        <div className="sg-card overflow-hidden">
            <Tabs
                index={tabIndex}
                onChange={(index) => onChangeTabIndex(index)}
                variant='unstyled'
                isLazy
            >
                <TabList className="flex gap-1 overflow-x-auto border-b border-slate-200/70 px-3 py-2.5 dark:border-white/[0.06]">
                    {tabs.map((tab, i) => (
                        <Tab
                            key={i}
                            className="flex shrink-0 items-center gap-2 !rounded-lg !px-3 !py-1.5 !text-[13px] !font-medium text-slate-500 transition-colors hover:text-slate-900 dark:text-slate-400 dark:hover:text-white"
                            _selected={{ bg: 'rgba(45,212,191,0.12)', color: 'var(--sg-text)', boxShadow: 'inset 0 0 0 1px rgba(45,212,191,0.28)' }}
                        >
                            {tab.icon}
                            {tab.label}
                        </Tab>
                    ))}
                </TabList>

                <TabPanels>
                    <TabPanel>
                        <Systems initialData={initialData?.sourcesystems} targets={initialData?.targetsystems} hostGroups={initialData?.hostgroups} />
                    </TabPanel>
                    <TabPanel>
                        <Targets initialData={initialData?.targetsystems} sources={initialData?.sourcesystems} hostGroups={initialData?.hostgroups} />
                    </TabPanel>
                    <TabPanel>
                        <HostGroups initialData={initialData?.hostgroups} targets={initialData?.targetsystems} sources={initialData?.sourcesystems} />
                    </TabPanel>
                </TabPanels>
            </Tabs>
        </div>
    );
}